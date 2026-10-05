package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrderedListAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val start: Int? = null
) : DocumentAttributes {

    override fun withClasses(classes: String?): OrderedListAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("orderedList")
data class OrderedListNode(
    @SerialName("attrs")
    override val attributes: OrderedListAttributes = OrderedListAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode