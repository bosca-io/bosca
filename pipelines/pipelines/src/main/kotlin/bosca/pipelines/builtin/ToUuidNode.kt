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
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parses the inbound text into a typed [SlotKind.UUID] so it can feed a UUID input (e.g. a "Get X"
 * resolver) — the textual inverse of [ToStringNode].
 *
 * Connect-time validation never widens an unknown/string value into a UUID slot — an identifier is not
 * arbitrary text — so when a UUID arrives in string form (a JSONata result, a config value, an id read
 * as text) this node is the sanctioned bridge to a well-typed UUID wire. A bare UUID passes straight
 * through. Complementary to [GetIdNode], which *extracts* a UUID from an entity/event field rather than
 * parsing free text.
 *
 * A non-string input, or text that is not a valid UUID, fails the node rather than producing a bad id.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "To UUID",
    description = "Parses the inbound text into a typed UUID so it can feed a UUID input.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.ANY,
            typeLabel = "Text or id",
            description = "A UUID in text form (or a bare UUID) to parse into a typed UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.UUID,
            typeLabel = "Id (UUID)",
            description = "The parsed identifier — feeds a Get X resolver or other UUID input.",
        ),
    ],
)
@Serializable
@SerialName("toUuid")
class ToUuidNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = ToUuidNodeSerializer.deserialize(context, inputs)
        val text = (input.`in`.encode(context.json) as? JsonPrimitive)?.contentOrNull?.trim()
            ?: error("To UUID node '${name.ifBlank { id }}': expected a UUID in text form, but the input was not a string")
        return try {
            ToUuidNodeSerializer.serialize(UUID.parse(text))
        } catch (e: IllegalArgumentException) {
            error("To UUID node '${name.ifBlank { id }}': '$text' is not a valid UUID")
        }
    }
}
