package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TableRowNodeAttributes(
    @SerialName("class")
    override val classes: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): TableRowNodeAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("tableRow")
data class TableRowNode(
    @SerialName("attrs")
    override val attributes: TableRowNodeAttributes = TableRowNodeAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
