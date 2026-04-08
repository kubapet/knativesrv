import kotlinx.serialization.Serializable

@Serializable
data class Jwk(
    val alg: String?,
    val kty: String,
    val use: String?,
    val x5c: List<String>?,
    val e: String?,
    val n: String?,
    val kid: String?,
    val x5t: String?
)

@Serializable
data class Jwks(var keys: List<Jwk> = listOf())