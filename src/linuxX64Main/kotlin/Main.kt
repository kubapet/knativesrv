import LogSeverity.*
import Shared.logger
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size != 1) {
        logger(SEVERE, "Usage: server.kexe <port>\n")
        exitProcess(1)
    }

    val port = args[0].toInt()

    Server(port) { with(request) { when {
        method != HttpRequestMethod.GET -> errorPage(method.toString(), HttpStatus.NotImplemented)
        uri == "/" -> html("<b>Ahoj!</b>")
        uri == "/test" -> runBlocking {
            val data = HttpClient(Curl).get<String> {
                url("http://google.com")
            }
            //html(data)
        }
        uri == "/tux.png" -> staticFile("tux.png", "image/png")
        else -> errorPage(uri, HttpStatus.NotFound)
    } } }.serve()
}