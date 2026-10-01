package bosca.hubspot.pipeline

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.di.provide
import bosca.hubspot.client.HubSpot
import bosca.hubspot.configuration.HubSpotConfiguration
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action contributed by `integrations/hubspot`: add a profile's HubSpot contact to **every list
 * configured** in the HubSpot integration settings (`listIds`). Reads the profile
 * [bosca.serialization.UUID] on `profile`, resolves the contact's HubSpot id, and adds it to each
 * configured list — the config-driven list fan-out the monolithic legacy sync job performed on a
 * newly-synced contact, now wireable per pipeline. [willSuspend] makes it durable. Passes the profile
 * id through so the run can continue.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Add to Configured HubSpot Lists",
    description = "Add a profile's HubSpot contact to every list configured in the HubSpot settings.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile whose contact to add — a UUID."),
    ],
    outputs = [
        OutputSlot(name = "profile", kind = SlotKind.UUID),
    ]
)
@Serializable
@SerialName("hubspotAddToConfiguredLists")
class AddToConfiguredHubSpotListsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure message layered on top.
        val profileId = AddToConfiguredHubSpotListsNodeSerializer.deserializePartial(context, inputs).profile
            ?: error("Add to Configured HubSpot Lists node '${name.ifBlank { id }}' requires a profile UUID on its 'profile' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "addToConfiguredHubSpotLists")
            put("profileId", profileId.toString())
            put("listIds", JsonArray(configuredListIds(context).map { JsonPrimitive(it) }))
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val profileId = AddToConfiguredHubSpotListsNodeSerializer.deserialize(context, inputs).profile
        val hubspot = provide<HubSpot>()
        val hubspotId = provide<ProfileService>().getAttributes(profileId).getAttributeString("bosca.profiles.hubspot.id", "id")
            ?: error("No HubSpot id for profile $profileId")
        for (listId in configuredListIds(context)) {
            hubspot.addToList(listId, hubspotId)
        }
        return inputs.first
    }

    private suspend fun configuredListIds(context: PipelineContext): List<String> =
        (provide<ConfigurationService>().getValueAs<HubSpotConfiguration>("hubspot", context.json)
            ?: error("Hubspot configuration not found")).listIds ?: emptyList()
}
