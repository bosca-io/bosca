package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TableCellNodeAttributes(
    @SerialName("class")
    override val classes: String? = null,
    var colspan: Int = 1,
    var rowspan: Int = 1
) : DocumentAttributes {

    override fun withClasses(classes: String?): TableCellNodeAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("tableCell")
data class TableCellNode(
    @SerialName("attrs")
    override val attributes: TableCellNodeAttributes = TableCellNodeAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
