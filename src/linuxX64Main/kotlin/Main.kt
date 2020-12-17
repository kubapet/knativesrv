import Severity.*
import Shared.logger
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import platform.posix.*
import kotlin.native.concurrent.DetachedObjectGraph
import kotlin.native.concurrent.SharedImmutable
import kotlin.native.concurrent.attach
import kotlin.system.exitProcess

object Shared {
    private val jwksPtr = DetachedObjectGraph {
        Jwks(listOf(Jwk(
            alg = "RS256",
            kty = "RSA",
            use = "sig",
            kid = myKeyPair.keyId,
            e = Base64.encode(myKeyPair.publicExponent),
            n = Base64.encode(myKeyPair.modulus),
            x5c = null,
            x5t = null
        )))
    }.asCPointer()

    private val loggerPtr = DetachedObjectGraph { Logger() }.asCPointer()

    val jwks get() = DetachedObjectGraph<Jwks>(jwksPtr).attach()
    val logger get() = DetachedObjectGraph<Logger>(loggerPtr).attach()
}

@SharedImmutable
val myKeyPair = RSAKeyPair.generateNew()

fun main(args: Array<String>) {
    if (args.size != 1) {
        logger(SEVERE, "usage: server.kexe <port>\n")
        exitProcess(1)
    }

    val port = atoi(args[0])

    Server(port) { with(request) { when {
        method != HttpRequestMethod.GET -> errorPage(
            method.toString(),
            HttpStatus.NotImplemented
        )
        uri == "/" -> runBlocking {
            val token = getToken(
                "http://localhost:3200",
                "https://localhost:8080",
                myKeyPair
            )
            val packed = token.pack()
            val data = HttpClient(Curl).get<String> {
                url("http://localhost:8080/secure/hello")
                header("Authorization", "Bearer $packed")
            }
            html(data)
        }
        uri == "/secure/hello" -> runBlocking {
            val authResult = authorizeToken(request)
            if (authResult.status == HttpStatus.Ok) {
                html("Hello ${authResult.token?.payload?.iss}")
            } else {
                errorPage(authResult.message, authResult.status)
            }
        }
        uri == "/.well-known/jwks.json" -> json(Shared.jwks)
        uri == "/health" -> json(NodeHealth("OK"))
        uri == "/metadata" -> json(
            NodeMetadata(
                "Jakub's Kotlin Native node",
                "A node written in Kotlin/Native",
                "Jakub Petrzilka",
                listOf("/health", "/metadata")
            )
        )
        uri == "/profile.png" -> staticFile("profile.png", "image/png")
        else -> errorPage(uri, HttpStatus.NotFound)
    } } }.serve()
}