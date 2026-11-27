package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("html")
data class HtmlNode(
    override val marks: List<Mark> = emptyList(),
    @SerialName("attrs")
    @Serializable(with = DocumentAttributesSerializer::class)
    override val attributes: DocumentAttributes = EmptyDocumentAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    val html: String,
) : DocumentNode
