package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("hardBreak")
data class HardBreakNode(
    @SerialName("attrs")
    override val attributes: DocumentAttributes = EmptyDocumentAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode