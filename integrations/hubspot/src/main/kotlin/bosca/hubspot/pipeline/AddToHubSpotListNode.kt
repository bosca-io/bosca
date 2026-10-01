package bosca.hubspot.pipeline

import bosca.di.provide
import bosca.hubspot.client.HubSpot
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Add to HubSpot List",
    description = "Add a profile's HubSpot contact to a HubSpot list (creating the contact first if needed).",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile whose contact to add — a UUID."),
    ],
    outputs = [
        OutputSlot(name = "profile", kind = SlotKind.UUID),
    ],
    settings = [
        SettingSlot(
            name = "listId", control = SettingControl.TEXT, label = "List id", required = true, mono = true, placeholder = "e.g. 42",
            description = "The HubSpot list to add the synced contact of the profile on the 'profile' input to (creating the contact first if needed).",
        ),
    ],
)
@Serializable
@SerialName("hubspotAddToList")
class AddToHubSpotListNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The HubSpot list id to add the contact to. */
    val listId: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure message layered on top.
        val profileId = AddToHubSpotListNodeSerializer.deserializePartial(context, inputs).profile
            ?: error("Add to HubSpot List node '${name.ifBlank { id }}' requires a profile UUID on its 'profile' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "addToHubSpotList")
            put("profileId", profileId.toString())
            put("listId", listId)
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val profileId = AddToHubSpotListNodeSerializer.deserialize(context, inputs).profile
        check(listId.isNotBlank()) { "Add to HubSpot List node '${name.ifBlank { id }}' needs a list id" }
        val profileService = provide<ProfileService>()
        val hubspot = provide<HubSpot>()
        val attributes = profileService.getAttributes(profileId)
        val hubspotId = attributes.getAttributeString("bosca.profiles.hubspot.id", "id") ?: error("No HubSpot id for profile $profileId")
        hubspot.addToList(listId, hubspotId)
        return inputs.first
    }
}
