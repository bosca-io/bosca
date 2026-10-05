package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TableNodeAttributes(
    @SerialName("class")
    override val classes: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): TableNodeAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("table")
data class TableNode(
    @SerialName("attrs")
    override val attributes: TableNodeAttributes = TableNodeAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
