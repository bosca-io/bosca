package bosca.pipelines.builtin

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/**
 * Flattens a nested array by one level — the "flatten" of a map/flatten (flatMap). A For Each whose body
 * yields a list per item produces an array of arrays (`X[][]`); wiring it through here collapses that to a
 * single `X[]`. Each inbound element that is itself an array is spread; a non-array element is kept as-is,
 * so a partly-flat array flattens leniently. Pure — no side effect.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Flatten",
    description = "Flattens a nested array one level (X[][] → X[]) — e.g. the array-of-arrays a For Each yields when each item produced a list.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in", kind = SlotKind.ARRAY, typeLabel = "Nested array",
            description = "An array whose elements are arrays — e.g. a For Each output.", required = true,
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.ARRAY, typeLabel = "Flattened array",
            description = "The inbound array flattened by one level.",
        ),
    ],
)
@Serializable
@SerialName("flatten")
class FlattenNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val array = inputs.first?.encode(context.json) as? JsonArray
            ?: error("Flatten node '${name.ifBlank { id }}' requires an array input")
        val flattened = buildList {
            for (element in array) {
                if (element is JsonArray) addAll(element) else add(element)
            }
        }
        return PipelineValue.ofJson(JsonArray(flattened))
    }
}
