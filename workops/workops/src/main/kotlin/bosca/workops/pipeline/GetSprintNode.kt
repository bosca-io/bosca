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
import bosca.workops.model.sprint.Sprint
import bosca.workops.service.SprintService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `workops`: loads the full [Sprint] for an inbound sprint [UUID] via
 * [SprintService], under the run's principal. Output carries `Sprint.serializer()` so downstream
 * nodes can read its fields or convert it to JSON.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Sprint",
    description = "Loads the full Sprint for a sprint id.",
    group = "WorkOps",
    subgroup = "Planning",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Sprint id",
            description = "The sprint's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Sprint::class,
            typeLabel = "Sprint",
            description = "The full Sprint.",
        ),
    ],
)
@Serializable
@SerialName("sprint.get")
class GetSprintNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val sprintId = GetSprintNodeSerializer.deserialize(context, inputs).`in`
        val sprint = provide<SprintService>().getById(sprintId)
            ?: error("Get Sprint node '${name.ifBlank { id }}': sprint $sprintId not found")
        return GetSprintNodeSerializer.serialize(sprint)
    }
}
