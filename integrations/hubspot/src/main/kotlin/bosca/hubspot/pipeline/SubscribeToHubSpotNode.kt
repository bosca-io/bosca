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

/**
 * Action contributed by `integrations/hubspot`: subscribe a profile's contact to a HubSpot
 * communication subscription ([subscriptionId]). Reads the profile [bosca.serialization.UUID] on
 * `profile`, resolves the contact's email and sets the subscription — one of the steps the legacy sync
 * job fanned out, now wireable per pipeline (one node per
 * subscription). [willSuspend] makes it durable: in a durable run the subscribe runs inside a child of
 * the run job (retried, its outcome reflected on the run) rather than fire-and-forget; the node emits a
 * receipt downstream.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Subscribe to HubSpot",
    description = "Subscribe a profile's HubSpot contact to a communication subscription.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile whose contact to subscribe — a UUID."),
    ],
    outputs = [
        OutputSlot(
            name = "out",
            kind = SlotKind.OBJECT,
            typeLabel = "Result",
            description = "The profile and the subscription it was subscribed to.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "subscriptionId", control = SettingControl.TEXT, label = "Subscription id", required = true, mono = true, placeholder = "e.g. 7",
            description = "The HubSpot communication subscription to subscribe the contact to (a numeric subscription id). Uses the email of the profile on the 'profile' input.",
        ),
    ],
)
@Serializable
@SerialName("hubspotSubscribe")
class SubscribeToHubSpotNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The HubSpot communication subscription id to subscribe the contact to. */
    val subscriptionId: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure message layered on top.
        val profileId = SubscribeToHubSpotNodeSerializer.deserializePartial(context, inputs).profile
            ?: error("Subscribe to HubSpot node '${name.ifBlank { id }}' requires a profile UUID on its 'profile' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "subscribeToHubSpot")
            put("profileId", profileId.toString())
            put("subscriptionId", subscriptionId)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val profileId = SubscribeToHubSpotNodeSerializer.deserialize(context, inputs).profile
        check(subscriptionId.isNotBlank()) { "Subscribe to HubSpot node '${name.ifBlank { id }}' needs a subscription id" }
        val profileService = provide<ProfileService>()
        val hubspot = provide<HubSpot>()
        val email = profileService.getAttributes(profileId).getAttributeString("bosca.profiles.email", "email")
            ?: error("No email found for profile $profileId")
        hubspot.setSubscription(email, subscriptionId.toInt())
        return null
    }
}
