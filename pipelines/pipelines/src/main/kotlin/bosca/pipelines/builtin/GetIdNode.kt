package bosca.pipelines.builtin

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.pipelines.node.entityRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Extracts an entity identifier as a typed [SlotKind.UUID] so it can feed a "Get X" resolver, whose
 * input is a bare UUID. The inbound value may be the entity (or event) carrying the id under [field]
 * — default `id`, set it to a typed event's prefixed field such as `taskId` — or it may already be a
 * bare UUID, which passes straight through.
 *
 * This is the one built-in node that **declares** a UUID output, so it is the sanctioned bridge from
 * a dynamic/unknown value (an Input event, a JSONata result) into a resolver's UUID slot: the
 * connection validator refuses to wire an unknown value directly into a typed slot, and this node is
 * how an author makes that wire well-typed.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get Id",
    description = "Extracts a UUID from an entity or event (by field name) so it can feed a Get X resolver.",
    group = "Core",
    subgroup = "Transform",
    inputs = [
        InputSlot(
            name = "in",
            kind = SlotKind.ANY,
            typeLabel = "Entity, event, or id",
            description = "An entity or event carrying the id field, or a bare UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.UUID,
            typeLabel = "Id (UUID)",
            description = "The extracted identifier — feeds a Get X resolver.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "field", control = SettingControl.TEXT, label = "Id field", default = "id", placeholder = "id",
            description = "Reads a UUID from this field of the inbound entity or event (e.g. id, or a typed event's taskId). A bare UUID passes straight through.",
        ),
    ],
)
@Serializable
@SerialName("getId")
class GetIdNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
    /** The field to read the UUID from (e.g. `id`, or a typed event's `taskId`); a bare UUID input ignores it. */
    val field: String = "id",
) : TransformNode() {
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = GetIdNodeSerializer.deserialize(context, inputs)
        val ref = input.`in`.entityRef(context.json, idFields = listOf(field))
            ?: error("Get Id node '${name.ifBlank { id }}': no '$field' UUID found in the input (and it is not a bare UUID)")
        return GetIdNodeSerializer.serialize(ref.id)
    }
}
