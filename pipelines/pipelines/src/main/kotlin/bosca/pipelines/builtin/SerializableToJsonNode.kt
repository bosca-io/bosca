package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bridges any inbound value to its JSON form. Trivially native-safe: the incoming [PipelineValue]
 * already carries its serializer, so this is just an encode — no reflective lookup, no
 * configuration. The typed serializer is retained as the value's origin, so a downstream
 * `JSON → Typed` node can convert back.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Serializable → JSON",
    description = "Converts a typed value to its JSON form so JSONata and other JSON nodes can work with it.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Value",
            description = "Any typed value to convert to its JSON form; its origin type is retained so a downstream JSON → Typed can convert back.",
        ),
    ],
)
@Serializable
@SerialName("serializableToJson")
class SerializableToJsonNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = inputs.first ?: error("SerializableToJson node '$id' requires an input")
        return input.toJson(context.json)
    }
}
