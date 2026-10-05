package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListItemAttributes(
    @SerialName("class")
    override val classes: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): ListItemAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("listItem")
data class ListItemNode(
    @SerialName("attrs")
    override val attributes: ListItemAttributes = ListItemAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode