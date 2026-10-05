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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action contributed by `integrations/hubspot`: send a HubSpot custom behavioral event attributed to a
 * profile's synced contact — the work the legacy `SendHubSpotEventJob` performs. Reads the profile
 * [bosca.serialization.UUID] on `profile`, resolves its `bosca.profiles.hubspot.id`, and sends
 * [eventName] with the inbound `properties` (a JSON object, e.g. from a JSONata node), timed at the
 * run's input occurrence. [willSuspend] makes it durable; the profile id passes through.
 *
 * Errors when the contact has no HubSpot id yet, so the run retries until the contact sync completes.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Send HubSpot Event",
    description = "Send a HubSpot custom behavioral event attributed to a profile's synced contact.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile whose contact the event is attributed to — a UUID."),
        InputSlot(
            name = "properties",
            kind = SlotKind.OBJECT,
            type = JsonElement::class,
            typeLabel = "Event properties",
            description = "The event property name/value pairs (e.g. from a JSONata node); optional.",
            required = false,
        ),
    ],
    outputs = [
        OutputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile the event was attributed to."),
    ],
    settings = [
        SettingSlot(
            name = "eventName", control = SettingControl.TEXT, label = "Event name", required = true, mono = true,
            placeholder = "e.g. pe1234567_signed_up",
            description = "The fully-qualified internal HubSpot event name (pe<portalId>_<name>), attributed to the synced HubSpot contact of the profile on the 'profile' input. Feed 'properties' the event's values. The run retries until the contact has finished syncing.",
        ),
    ],
)
@Serializable
@SerialName("hubspotSendEvent")
class SendHubSpotEventNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The fully-qualified internal HubSpot event name, e.g. `pe<portalId>_<name>`. */
    val eventName: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode — this node's own profile failure message layered on top; the optional
        // 'properties' input stays tolerant.
        val input = SendHubSpotEventNodeSerializer.deserializePartial(context, inputs)
        val profileId = input.profile
            ?: error("Send HubSpot Event node '${name.ifBlank { id }}' requires a profile UUID on its 'profile' input")
        val properties = input.properties as? JsonObject ?: JsonObject(emptyMap())
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "sendHubSpotEvent")
            put("profileId", profileId.toString())
            put("eventName", eventName)
            put("properties", properties)
        })
        return inputs["profile"]
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = SendHubSpotEventNodeSerializer.deserialize(context, inputs)
        check(eventName.isNotBlank()) { "Send HubSpot Event node '${name.ifBlank { id }}' needs an event name" }
        val objectId = provide<ProfileService>().getAttributes(input.profile).getAttributeString("bosca.profiles.hubspot.id", "id")
            ?: error("Profile ${input.profile} has no HubSpot contact id yet")
        provide<HubSpot>().sendEvent(
            eventName = eventName,
            objectId = objectId,
            properties = input.properties as? JsonObject ?: JsonObject(emptyMap()),
            occurredAt = context.inputCreated,
        )
        return inputs["profile"]
    }
}
