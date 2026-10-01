package bosca.cli.documents

import bosca.cli.documents.marks.Mark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TaskListAttributes(
    @SerialName("class")
    override val classes: String? = null,
) : DocumentAttributes {

    override fun withClasses(classes: String?): TaskListAttributes {
        return copy(classes = classes)
    }
}

@Serializable
@SerialName("taskList")
data class TaskListNode(
    @SerialName("attrs")
    override val attributes: TaskListAttributes = TaskListAttributes(),
    override var content: List<DocumentNode> = emptyList(),
    override val marks: List<Mark> = emptyList(),
) : DocumentNode
