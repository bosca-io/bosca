package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HeadingAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val level: Int,
    val textAlign: String? = null
) : DocumentAttributes {

    override fun withClasses(classes: String?): HeadingAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("heading")
data class HeadingNode(
    @SerialName("attrs")
    override val attributes: HeadingAttributes,
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode