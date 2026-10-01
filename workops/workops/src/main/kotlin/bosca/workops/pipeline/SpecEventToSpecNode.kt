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
import bosca.workops.model.spec.Spec
import bosca.workops.service.SpecService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resolver node contributed by `workops`: loads the full [Spec] for an inbound spec [UUID] via
 * [SpecService], under the run's principal. Output carries `Spec.serializer()`.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Spec",
    description = "Loads the full Spec for a spec id.",
    group = "WorkOps",
    subgroup = "Planning",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.UUID,
            typeLabel = "Spec id",
            description = "The spec's UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            type = Spec::class,
            typeLabel = "Spec",
            description = "The full Spec.",
        ),
    ],
)
@Serializable
@SerialName("spec.fromEvent")
class SpecEventToSpecNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val specId = SpecEventToSpecNodeSerializer.deserialize(context, inputs).`in`
        val spec = provide<SpecService>().getById(specId)
            ?: error("Get Spec node '${name.ifBlank { id }}': spec $specId not found")
        return SpecEventToSpecNodeSerializer.serialize(spec)
    }
}
