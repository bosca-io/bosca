package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CodeBlockAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val language: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): CodeBlockAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("codeBlock")
data class CodeBlockNode(
    @SerialName("attrs")
    override val attributes: CodeBlockAttributes = CodeBlockAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
