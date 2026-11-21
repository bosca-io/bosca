package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
data class TableCellNodeAttributes(
    @SerialName("class")
    override val classes: String? = null,
    var colspan: Int = 1,
    var rowspan: Int = 1
) : DocumentAttributes {

    var colwidth: List<Int>? = null

    override fun withClasses(classes: String?): TableCellNodeAttributes {
        return copy(classes = classes).also { it.colwidth = colwidth }
    }
}

@Serializable
@SerialName("tableCell")
data class TableCellNode(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    @SerialName("attrs")
    override val attributes: TableCellNodeAttributes = TableCellNodeAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
