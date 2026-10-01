package bosca.hubspot.pipeline

import bosca.di.provide
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
import bosca.profile.attribute.model.ProfileAttributeInput
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Add HubSpot Profile Attribute",
    description = "Record a HubSpot record id onto a profile so a later sync updates rather than duplicates.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(
            name = "profile",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile to stamp — a UUID.",
        ),
        InputSlot(
            name = "id",
            kind = SlotKind.STRING,
            typeLabel = "HubSpot id",
            description = "The HubSpot record id — a string (e.g. the `id` from a HubSpot Write node's output).",
        ),
    ],
    outputs = [
        OutputSlot("profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The stamped profile, passed through so later steps run after the id is recorded."),
    ],
)
@Serializable
@SerialName("hubspotAddProfileAttribute")
class AddHubSpotProfileAttributeNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        // Lenient decode, with this node's own per-port failure messages layered on top.
        val label = name.ifBlank { id }
        val input = AddHubSpotProfileAttributeNodeSerializer.deserializePartial(context, inputs)
        val profileId = input.profile
            ?: error("Add HubSpot Id node '$label' requires a profile UUID on its 'profile' input")
        val hubspotId = input.id
            ?: error("Add HubSpot Id node '$label' requires a HubSpot id on its 'id' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "addHubSpotId")
            put("profileId", profileId.toString())
            put("id", hubspotId)
        })
        return AddHubSpotProfileAttributeNodeSerializer.serialize(profileId)
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = AddHubSpotProfileAttributeNodeSerializer.deserialize(context, inputs)
        val service = provide<ProfileService>()
        val attributes = service.getAttributes(input.profile)

        attributes.getAttributeString("bosca.profiles.hubspot.id", "id")?.let {
            if (it == input.id) {
                return AddHubSpotProfileAttributeNodeSerializer.serialize(input.profile)
            } else {
                error("Profile ${input.profile} already has a HubSpot id of $it")
            }
        }

        service.addAttributes(
            input.profile,
            listOf(
                ProfileAttributeInput(
                    attributes = JsonObject(mapOf("id" to JsonPrimitive(input.id))),
                    confidence = 100,
                    priority = 100,
                    source = "pipeline",
                    typeId = "bosca.profiles.hubspot.id",
                    visibility = ProfileVisibility.SYSTEM
                )
            )
        )
        return AddHubSpotProfileAttributeNodeSerializer.serialize(input.profile)
    }
}
