import Shared.logger
import kotlinx.cinterop.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.*
import kotlin.native.concurrent.*
import kotlin.system.exitProcess

const val bufferSize = 1024

enum class HttpRequestMethod {
    GET, HEAD, POST, PUT, CONNECT, OPTIONS, TRACE, PATCH
}

enum class HttpStatus(val code: Int, val shortMessage: String, val longMessage: String) {
    Ok(200, "OK", ""),
    BadRequest(400, "Bad Request", "This server understands no the dialect of your tribe"),
    Unauthorized(401, "Unauthorized", "Request authorization has failed"),
    NotFound(404, "Not Found", "The requested page or resource was not found"),
    NotImplemented(501, "Not Implemented", "The requested method is not implemented"),
}

enum class ServerError(val returnValue: Int, val longMessage: String) {
    OpenSocketError(30, "Unable to open server socket"),
    BindingError(31, "Unable to bind to specified address and port"),
    ListeningError(32, "Unable to listen on specified port"),
    AcceptConnectionError(50, "Unable to accept client connection"),
    GetClientInfoError(51, "Unable to get client hostname or ip address"),
    OpenStreamError(52, "Unable to open stream for communication with client"),
}

data class HttpRequest(
    val method: HttpRequestMethod,
    val uri: String,
    val version: String,
    val headers: Map<String, String>
)

class Server(private val srvPort: Int, private val routingConfig: ResponseBuilder.() -> Unit) {
    private var srvSocketFD: Int = 0
    private val futures = mutableListOf<Future<Unit>>()

    fun serve() = memScoped {
        val options = alloc<IntVar> { value = 1 }
        val srvAddress = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_addr.s_addr = htonl(INADDR_ANY)
            sin_port = htons(srvPort.convert())
            //memset(sin_zero.getPointer(this@memScoped), 0, sizeOf<sockaddr_in>().toULong())
        }

        srvSocketFD = socket(AF_INET, SOCK_STREAM, 0)
        assert(srvSocketFD, ServerError.OpenSocketError)

        setsockopt(srvSocketFD, SOL_SOCKET, SO_REUSEADDR, options.ptr, sizeOf<IntVar>().convert())

        val bindResult = bind(srvSocketFD, srvAddress.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        assert(bindResult, ServerError.BindingError)

        val listenResult = listen(srvSocketFD, 10)
        assert(listenResult, ServerError.ListeningError)

        repeat(10) { workerId ->
            val workerContext = WorkerContext("worker-$workerId", srvSocketFD, routingConfig)
            val future = Worker.start().execute(TransferMode.SAFE, { workerContext.freeze() }) {
                while(true) it.processRequest()
            }
            futures.add(future)
        }

        futures.forEach { it.consume { } }
    }
}

data class WorkerContext(val workerId: String, val srvSocketFD: Int, val routingConfig: ResponseBuilder.() -> Unit) {
    fun processRequest() = memScoped {
        logger.positive(workerId, "waiting for connection")

        val clientAddress = alloc<sockaddr_in>()
        val addrLen = alloc<socklen_tVar> { value = sizeOf<sockaddr_in>().convert() }
        val clientFD = accept(srvSocketFD, clientAddress.ptr.reinterpret(), addrLen.ptr)
        assert(clientFD, ServerError.AcceptConnectionError)

        val clientAddr = alloc<in_addr_tVar> { value = clientAddress.sin_addr.s_addr }
        val clientInfo = gethostbyaddr(clientAddr.ptr, sizeOf<in_addr_tVar>().convert(), AF_INET)
        assert(clientInfo, ServerError.GetClientInfoError)

        val stream = fdopen(clientFD, "r+")
        assert(stream, ServerError.OpenStreamError)

        val request = parseRequest(stream!!)
        logger.debug(workerId, "recieved request:").debugData(request)

        ResponseBuilder(request, stream).run(routingConfig)

        fflush(stream)
        fclose(stream)
        close(clientFD)
    }

    private fun parseRequest(stream: CValuesRef<FILE>): HttpRequest = memScoped {
        val buffer = allocArray<ByteVar>(bufferSize)
        fgets(buffer, bufferSize, stream)

        val requestLine = buffer.toKString().split(" ")

        if (requestLine.size != 3) {
            logger.warn(workerId, "invalid request line:").debugData(requestLine)
            HttpRequest(HttpRequestMethod.GET, "", "", emptyMap())
        } else {
            val headers = mutableMapOf<String, String>()
            while (fgets(buffer, bufferSize, stream)?.toKString() != "\r\n") {
                val keyValueArray = buffer.toKString().split(": ")
                if (keyValueArray.size != 2) {
                    logger.warn(workerId, "invalid header line:").debugData(buffer.toKString())
                } else {
                    headers[keyValueArray[0]] = keyValueArray[1]
                }
            }
            HttpRequest(HttpRequestMethod.valueOf(requestLine[0]), requestLine[1], requestLine[2], headers)
        }
    }
}

class ResponseBuilder(val request: HttpRequest, private val stream: CValuesRef<FILE>) {
    fun okHeader(contentType: String, contentLength: size_t) = header(HttpStatus.Ok, contentType, contentLength)
    fun header(status: HttpStatus, contentType: String, contentLength: size_t) {
        val header = """
        HTTP/1.1 ${status.code} ${status.shortMessage}
        Content-Type: $contentType
        Content-Length: $contentLength
        """
        fputs(header.trimIndent() + "\n\n", stream)
    }

    fun errorPage(cause: String, status: HttpStatus) {
        val html = """
        <html>
          <head><title>Error</title></head>
          <body>${status.code}: ${status.shortMessage}<p>${status.longMessage}: $cause</p></body>
        </html>
        """.trimIndent()
        header(status, "text/html", html.length.convert())
        fputs(html + "\n\n", stream)
    }

    fun html(html: String) = generic(html,"text/html")
    fun json(json: String) = generic(json, "application/json")
    inline fun <reified T> json(data: T) = generic(Json.encodeToString(data), "application/json")

    fun generic(body: String, contentType: String) {
        okHeader(contentType, body.length.convert())
        fprintf(stream, body)
    }

    fun staticFile(fileName: String, contentType: String) = memScoped {
        val fileStatus = alloc<stat>()
        stat(fileName, fileStatus.ptr)
        val fileSize = fileStatus.st_size

        val buffer = allocArray<ByteVar>(fileSize)
        val ifd: CPointer<FILE>? = fopen(fileName, "r" )
        fread(buffer, sizeOf<ByteVar>().convert(), fileSize.convert(), ifd)
        fclose(ifd)

        okHeader(contentType, fileSize.convert())
        fwrite(buffer, sizeOf<ByteVar>().convert(), fileSize.convert(), stream)
    }
}

private fun assert(value: Any?, error: ServerError) {
    if (value == null || value is Int && value < 0) {
        logger.error("dunno", error.longMessage)
        exitProcess(error.returnValue)
    }
}