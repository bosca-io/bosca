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
import bosca.workops.model.requirement.Requirement
import bosca.workops.service.RequirementService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `workops`: loads the full [Requirement] for an inbound requirement
 * [UUID] via [RequirementService], under the run's principal. Output carries
 * `Requirement.serializer()`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Requirement",
    description = "Loads the full Requirement for a requirement id.",
    group = "WorkOps",
    subgroup = "Planning",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Requirement id",
            description = "The requirement's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Requirement::class,
            typeLabel = "Requirement",
            description = "The full Requirement.",
        ),
    ],
)
@Serializable
@SerialName("requirement.fromEvent")
class RequirementEventToRequirementNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val requirementId = RequirementEventToRequirementNodeSerializer.deserialize(context, inputs).`in`
        val requirement = provide<RequirementService>().getById(requirementId)
            ?: error("Get Requirement node '${name.ifBlank { id }}': requirement $requirementId not found")
        return RequirementEventToRequirementNodeSerializer.serialize(requirement)
    }
}
