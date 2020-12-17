import kotlinx.serialization.*

@Serializable
data class NodeHealth(val status: String)

@Serializable
data class NodeMetadata(
    val name: String,
    val description: String?,
    val owner: String?,
    val services: List<String>?
)