package bosca.profile.profile.pipeline

import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.TransformNode
import bosca.profile.attribute.model.ProfileAttribute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Transform contributed by `social/profile`: selects a single [ProfileAttribute] out of an inbound
 * list by its [typeId] — the companion to "Get Profile Attributes", which produces the list. Wire
 * `Get Profile → Get Profile Attributes → Get Profile Attribute` to pull one attribute (e.g.
 * `bosca.profiles.email` or `bosca.profiles.hubspot.id`) out of a profile's attributes.
 *
 *  - `out` — the first attribute whose `typeId` matches; carries `ProfileAttribute.serializer()` so a
 *    downstream node bridges it to JSON natively (e.g. a JSONata node reading its `attributes`).
 *  - `notFound` (error port) — no attribute of [typeId] is present; emits `{ typeId, found: false }`.
 *    Wire it to handle the absence, or leave it unwired to fail the run (no silent miss).
 *
 * Operates purely on the inbound value (no service call), so it is safe in a dry run. Contributed to
 * the engine purely by the `@PipelineNodeType` annotation — no engine changes.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Get Profile Attribute",
    description = "Select one profile attribute from a list by its type id.",
    group = "Social",
    subgroup = "Profiles",
    inputs = [
        InputSlot(
            // ARRAY only (no specific `type`): the value carries a list serializer, whose serial name
            // is the collection's — not ProfileAttribute's — so a `type` constraint would reject it.
            name = "in",
            kind = SlotKind.ARRAY,
            typeLabel = "List of ProfileAttribute",
            description = "A profile's attributes (e.g. from Get Profile Attributes).",
        ),
    ],
    outputs = [
        OutputSlot("out", kind = SlotKind.OBJECT),
        OutputSlot("notFound", error = true),
    ],
    settings = [
        SettingSlot(
            name = "typeId", control = SettingControl.REFERENCE, reference = ReferenceSource.ATTRIBUTE_TYPE,
            label = "Attribute type", required = true, placeholder = "Pick an attribute type…",
            description = "Selects the first attribute of this type from the inbound list (e.g. from a Get Profile Attributes node). If none match, the notFound branch is taken.",
        ),
    ],
)
@Serializable
@SerialName("profile.getAttribute")
class GetProfileAttributeNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The attribute type id to select, e.g. `bosca.profiles.email`. */
    val typeId: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        require(typeId.isNotBlank()) { "Get Profile Attribute node '$label' requires a typeId to select" }
        val attributes = (inputs.first?.value as? List<*>)?.filterIsInstance<ProfileAttribute>()
            ?: error("Get Profile Attribute node '$label' requires a list of ProfileAttribute on its 'in' input")
        val match = attributes.firstOrNull { it.typeId == typeId }
            ?: return PipelineValue.ofJson(buildJsonObject {
                put("typeId", typeId)
                put("found", false)
            }).onPort("notFound")
        return PipelineValue.of(match, ProfileAttribute.serializer()).onPort("out")
    }
}
