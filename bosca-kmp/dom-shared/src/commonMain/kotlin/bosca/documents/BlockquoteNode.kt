package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("blockquote")
data class BlockquoteNode(
    @SerialName("attrs")
    @Serializable(with = DocumentAttributesSerializer::class)
    override val attributes: DocumentAttributes = EmptyDocumentAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
