package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TableHeaderNodeAttributes(
    @SerialName("class")
    override val classes: String? = null,
    var colspan: Int = 1,
    var rowspan: Int = 1
) : DocumentAttributes {

    override fun withClasses(classes: String?): TableHeaderNodeAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("tableHeader")
data class TableHeaderNode(
    @SerialName("attrs")
    override val attributes: TableHeaderNodeAttributes = TableHeaderNodeAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
