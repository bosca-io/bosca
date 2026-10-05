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
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renders the inbound value as text (a [SlotKind.STRING] output) so it can feed a string input slot.
 *
 * This is the **explicit** bridge for "value → string": connect-time validation never widens a typed
 * value (a UUID, a number) into a string slot — an identifier is not arbitrary text — so when a string
 * is genuinely wanted (e.g. a HubSpot contact id pulled from a write result, a UUID logged as text),
 * the author converts it here rather than relying on an implicit coercion.
 *
 * A JSON scalar (string, number, boolean, uuid, null) becomes its bare text; a structured value
 * (object/array) becomes its compact JSON form, so the node never fails on a non-scalar input.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "To String",
    description = "Converts the inbound value to text (a string) so it can feed a string input.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.ANY,
            typeLabel = "Any value",
            description = "Any value — an id, number, boolean, or other scalar — to render as text.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.STRING,
            typeLabel = "Text (string)",
            description = "The inbound value as text.",
        ),
    ],
)
@Serializable
@SerialName("toString")
class ToStringNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = ToStringNodeSerializer.deserialize(context, inputs)
        val element = input.`in`.encode(context.json)
        // A JSON scalar yields its bare content (unquoted); a structured value yields its JSON text.
        val text = (element as? JsonPrimitive)?.content ?: element.toString()
        return ToStringNodeSerializer.serialize(text)
    }
}
