package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TextAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val transform: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): TextAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("text")
data class TextNode(
    @SerialName("attrs")
    override val attributes: TextAttributes = TextAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
    val text: String,
) : DocumentNode