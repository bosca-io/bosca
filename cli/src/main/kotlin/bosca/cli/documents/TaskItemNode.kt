package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskItemAttributes(
    @SerialName("class")
    override val classes: String? = null,
    val checked: Boolean = false,
) : DocumentAttributes {

    override fun withClasses(classes: String?): TaskItemAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("taskItem")
data class TaskItemNode(
    @SerialName("attrs")
    override val attributes: TaskItemAttributes = TaskItemAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
