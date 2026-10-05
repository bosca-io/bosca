package bosca.documents

import bosca.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MentionAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val id: String? = null,
    val label: String? = null,
    val entityType: String? = null,
    val mentionSuggestionChar: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): MentionAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("mention")
data class MentionNode(
    @SerialName("attrs")
    override val attributes: MentionAttributes,
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
