package bosca.docs.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DocumentationDocument(
    val id: String,
    val name: String,
    val qualifiedName: String = "",
    val kind: String,
    val source: String,
    val module: String,
    @SerialName("pkg")
    val pkg: String = "",
    val category: String = "",
    val signature: String = "",
    val description: String = "",
    val content: String = "",
)
