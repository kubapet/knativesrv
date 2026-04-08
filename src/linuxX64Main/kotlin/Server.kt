import Severity.*
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

class Server(
    private val srvPort: Int,
    private val numberOfWorkers: Int = 10,
    private val queueSize: Int = 10,
    private val routingConfig: ResponseBuilder.() -> Unit
) {
    private var srvSocketFD: Int = 0
    private val futures = mutableListOf<Future<Unit>>()

    fun serve() = memScoped {
        val options = alloc<IntVar> { value = 1 }
        val srvAddress = alloc<sockaddr_in> {
            sin_family = AF_INET.convert()
            sin_addr.s_addr = htonl(INADDR_ANY)
            sin_port = htons(srvPort.convert())
        }

        srvSocketFD = socket(AF_INET, SOCK_STREAM, 0)
        assert(srvSocketFD, ServerError.OpenSocketError)

        setsockopt(srvSocketFD, SOL_SOCKET, SO_REUSEADDR, options.ptr, sizeOf<IntVar>().convert())

        val bindResult = bind(srvSocketFD, srvAddress.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
        assert(bindResult, ServerError.BindingError)

        val listenResult = listen(srvSocketFD, queueSize)
        assert(listenResult, ServerError.ListeningError)

        repeat(numberOfWorkers) { workerId ->
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
        logger(INFO, "waiting for connection", workerId)

        val clientAddress = alloc<sockaddr_in>()
        val addrLen = alloc<socklen_tVar> { value = sizeOf<sockaddr_in>().convert() }
        val clientFD = accept(srvSocketFD, clientAddress.ptr.reinterpret(), addrLen.ptr)
        assert(clientFD, ServerError.AcceptConnectionError, workerId)

        val clientAddr = alloc<in_addr_tVar> { value = clientAddress.sin_addr.s_addr }
        val clientInfo = gethostbyaddr(clientAddr.ptr, sizeOf<in_addr_tVar>().convert(), AF_INET)
        assert(clientInfo, ServerError.GetClientInfoError, workerId)

        val stream = fdopen(clientFD, "r+")
        assert(stream, ServerError.OpenStreamError, workerId)

        val request = parseRequest(stream!!)
        logger(DEBUG, "recieved request:\n$request", workerId)

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
            logger(WARN, "invalid request line:\n$requestLine", workerId)
            HttpRequest(HttpRequestMethod.GET, "", "", emptyMap())
        } else {
            val headers = mutableMapOf<String, String>()
            while (fgets(buffer, bufferSize, stream)?.toKString() != "\r\n") {
                val keyValueArray = buffer.toKString().split(": ")
                if (keyValueArray.size != 2) {
                    logger(WARN, "invalid header line:\n${buffer.toKString()}", workerId)
                } else {
                    headers[keyValueArray[0]] = keyValueArray[1]
                }
            }
            HttpRequest(HttpRequestMethod.valueOf(requestLine[0]), requestLine[1], requestLine[2], headers)
        }
    }
}

class ResponseBuilder(val request: HttpRequest, private val stream: CValuesRef<FILE>) {
    fun okHeader(contentType: String, contentLength: Int) = header(HttpStatus.Ok, contentType, contentLength)
    fun header(status: HttpStatus, contentType: String, contentLength: Int) {
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
        header(status, "text/html", html.length)
        fputs(html + "\n\n", stream)
    }

    fun html(html: String) = generic(html,"text/html")
    fun json(json: String) = generic(json, "application/json")
    inline fun <reified T> json(data: T) = generic(Json.encodeToString(data), "application/json")

    fun generic(body: String, contentType: String) {
        okHeader(contentType, body.length)
        fprintf(stream, body)
    }

    fun staticFile(fileName: String, contentType: String) = memScoped {
        val fileStatus = alloc<stat>()
        stat(fileName, fileStatus.ptr)
        val fileSize = fileStatus.st_size

        val ifd: CPointer<FILE>? = fopen(fileName, "r" )
        if (ifd  != null) {
            try {
                val buffer = allocArray<ByteVar>(fileSize)
                val fileSizeU: size_t = fileSize.convert()
                val elementSizeU: size_t = sizeOf<ByteVar>().convert()
                fread(buffer, elementSizeU, fileSizeU, ifd)
                okHeader(contentType, fileSize.convert())
                fwrite(buffer, elementSizeU, fileSizeU, stream)
            }
            finally {
                fclose(ifd)
            }
        }
        else {
            errorPage("Requested file was not found on the server", HttpStatus.NotFound)
        }
    }
}

private fun assert(value: Any?, error: ServerError, context: String? = null) {
    if (value == null || value is Int && value < 0) {
        logger(ERROR, error.longMessage, context)
        exitProcess(error.returnValue)
    }
}