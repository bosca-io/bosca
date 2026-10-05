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
 * Converts a JSON-form value back to its original type, using the origin serializer the value has
 * carried since `Serializable → JSON` (see [PipelineValue.decodeOrigin]) — no reflective lookup.
 * Fails clearly when the inbound JSON has no origin (plain JSON, or shape-changed by a JSONata /
 * ObjectsToMap node, where the original type no longer describes the data).
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "JSON → Typed",
    description = "Converts a JSON value back to the typed object it came from (requires an upstream Serializable → JSON).",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "JSON",
            description = "JSON produced by an upstream Serializable → JSON node — it carries the origin type this node converts back to.",
        ),
    ],
)
@Serializable
@SerialName("jsonToSerializable")
class JsonToSerializableNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = inputs.first ?: error("JSON → Typed node '$id' requires an input")
        return input.decodeOrigin(context.json)
            ?: error(
                "JSON → Typed node '${name.ifBlank { id }}': the inbound value carries no origin type — " +
                    "it must come from a Serializable → JSON node (JSONata/ObjectsToMap outputs change shape and cannot convert back)",
            )
    }
}
