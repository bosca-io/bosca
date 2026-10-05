package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Content(
    val document: DocumentNode = Document()
)

@Serializable
@SerialName("doc")
data class Document(
    @SerialName("attrs")
    override val attributes: DocumentAttributes = EmptyDocumentAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode

