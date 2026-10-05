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
 * Action contributed by `integrations/hubspot`: subscribe a profile's HubSpot contact to **every
 * communication subscription configured** in the HubSpot integration settings (`subscriptionIds`).
 * Reads the profile [bosca.serialization.UUID] on `profile`, resolves the contact's email, and sets
 * each configured subscription — the config-driven subscription fan-out the monolithic legacy sync
 * job performed on a newly-synced contact, now wireable per pipeline. [willSuspend] makes it durable.
 * Passes the profile id through so the run can continue.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Subscribe to Configured HubSpot Subscriptions",
    description = "Subscribe a profile's HubSpot contact to every subscription configured in the HubSpot settings.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile whose contact to subscribe — a UUID."),
    ],
    outputs = [
        OutputSlot(name = "profile", kind = SlotKind.UUID),
    ]
)
@Serializable
@SerialName("hubspotSubscribeToConfigured")
class SubscribeToConfiguredHubSpotNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure message layered on top.
        val profileId = SubscribeToConfiguredHubSpotNodeSerializer.deserializePartial(context, inputs).profile
            ?: error("Subscribe to Configured HubSpot Subscriptions node '${name.ifBlank { id }}' requires a profile UUID on its 'profile' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "subscribeToConfiguredHubSpot")
            put("profileId", profileId.toString())
            put("subscriptionIds", JsonArray(configuredSubscriptionIds(context).map { JsonPrimitive(it) }))
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val profileId = SubscribeToConfiguredHubSpotNodeSerializer.deserialize(context, inputs).profile
        val hubspot = provide<HubSpot>()
        val email = provide<ProfileService>().getAttributes(profileId).getAttributeString("bosca.profiles.email", "email")
            ?: error("No email found for profile $profileId")
        for (subscriptionId in configuredSubscriptionIds(context)) {
            hubspot.setSubscription(email, subscriptionId.toInt())
        }
        return inputs.first
    }

    private suspend fun configuredSubscriptionIds(context: PipelineContext): List<String> =
        (provide<ConfigurationService>().getValueAs<HubSpotConfiguration>("hubspot", context.json)
            ?: error("Hubspot configuration not found")).subscriptionIds ?: emptyList()
}
