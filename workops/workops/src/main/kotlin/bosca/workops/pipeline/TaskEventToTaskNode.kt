package bosca.workops.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.workops.model.task.Task
import bosca.workops.service.TaskService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `workops`: loads the full [Task] for an inbound task [UUID] via
 * [TaskService], under the run's principal. Output carries `Task.serializer()`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Task",
    description = "Loads the full Task for a task id.",
    group = "WorkOps",
    subgroup = "Planning",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Task id",
            description = "The task's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Task::class,
            typeLabel = "Task",
            description = "The full Task.",
        ),
    ],
)
@Serializable
@SerialName("task.fromEvent")
class TaskEventToTaskNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val taskId = TaskEventToTaskNodeSerializer.deserialize(context, inputs).`in`
        val task = provide<TaskService>().getById(taskId)
            ?: error("Get Task node '${name.ifBlank { id }}': task $taskId not found")
        return TaskEventToTaskNodeSerializer.serialize(task)
    }
}
