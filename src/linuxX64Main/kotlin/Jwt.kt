import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.request.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class JwtHeader(val alg: String, val typ: String, val kid: String)

@Serializable
data class JwtPayload(val iss: String, val sub: String, val aud: String, val iat: Long, val exp: Long)

@Serializable
data class Jwt(
    val header: JwtHeader,
    val payload: JwtPayload,
    @Transient val rsaKeyPair: RSAKeyPair? = null
) {
    constructor(
        alg: String,
        typ: String,
        iss: String,
        sub: String,
        aud: String,
        iat: Long,
        exp: Long,
        rsaKeyPair: RSAKeyPair
    ) : this(JwtHeader(alg, typ, rsaKeyPair.keyId), JwtPayload(iss, sub, aud, iat, exp), rsaKeyPair)

    fun pack(): String {
        val message = Base64.encode(Json.encodeToString(header)) + "." + Base64.encode(Json.encodeToString(payload))
        return message + "." + Base64.encode(rsaKeyPair!!.rs256Signature(message))
    }

}

fun getToken(selfUrl: String, targetUrl: String, rsaKeyPair: RSAKeyPair): Jwt {
    val currTimestamp = Clock.System.now().epochSeconds
    return Jwt(
        alg = "RS256",
        typ = "JWT",
        iss = selfUrl,
        sub = selfUrl,
        aud = targetUrl,
        iat = currTimestamp,
        exp = currTimestamp + 30,
        rsaKeyPair = rsaKeyPair
    )
}

data class AuthorizationResult(val status: HttpStatus, val message: String = "", val token: Jwt? = null)

suspend fun authorizeToken(request: HttpRequest): AuthorizationResult {
    val authHeader = request.headers["Authorization"]
    if (authHeader == null || authHeader.take(7) != "Bearer ") {
        return AuthorizationResult(HttpStatus.BadRequest, "Missing Authorization header")
    }

    val token = authHeader.drop(7)
    val tokenParts = token.split(".")
    if (tokenParts.size != 3) {
        return AuthorizationResult(HttpStatus.BadRequest, "Unable to parse Auth token")
    }

    val tokenHeader = Json.decodeFromString<JwtHeader>(Base64.decodeToString(tokenParts[0]))
    val tokenPayload = Json.decodeFromString<JwtPayload>(Base64.decodeToString(tokenParts[1]))
    val signature = Base64.decodeToUByteArray(tokenParts[2].trim())

    val data = HttpClient(Curl).get<String> {
        url("${tokenPayload.iss}/.well-known/jwks.json")
    }

    val receivedJwks = Json.decodeFromString<Jwks>(data)
    val receivedJwk = receivedJwks.keys.find { it.kid == tokenHeader.kid }
        ?: return AuthorizationResult(
            HttpStatus.BadRequest,
            "Unable to obtain JWK from ${tokenPayload.iss}/.well-known/jwks.json",
            Jwt(tokenHeader, tokenPayload)
        )

    val receivedKeyPair = RSAKeyPair.fromB64PublicExponentAndModulus(receivedJwk.e!!, receivedJwk.n!!)
    val authorized = receivedKeyPair.verifyRs256Signature(tokenParts[0] + "." + tokenParts[1], signature)

    return if (authorized)
        AuthorizationResult(HttpStatus.Ok, token = Jwt(tokenHeader, tokenPayload, receivedKeyPair))
    else
        AuthorizationResult(
            HttpStatus.Unauthorized,
            "Token authorization failed",
            token = Jwt(tokenHeader, tokenPayload, receivedKeyPair)
        )
}