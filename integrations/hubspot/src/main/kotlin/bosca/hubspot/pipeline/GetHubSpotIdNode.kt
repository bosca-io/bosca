package bosca.hubspot.pipeline

import bosca.di.provide
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
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.profile.service.ProfileService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fetch contributed by `integrations/hubspot`: resolve a profile's HubSpot object id — the
 * `bosca.profiles.hubspot.id` attribute a prior sync stamped (see [AddHubSpotProfileAttributeNode]).
 * Reads the profile [bosca.serialization.UUID] on `profile` and emits the id on `out`.
 *
 * Pairs with [RouteByHubSpotSyncNode], which decides add-vs-update by whether a profile is already
 * synced: this fetch supplies the id on the **update** branch, where the id is guaranteed present —
 * so a missing id here is a real error, not a branch. A pure read with no side effect, so it runs
 * unchanged in a dry run.
 *
 * Contributed to the engine purely by the `@PipelineNodeType` annotation — no engine changes.
 */
@PipelineNodeType(
    category = NodeCategory.FETCH,
    label = "Get HubSpot Id",
    description = "Resolve a profile's existing HubSpot object id.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(
            name = "profile",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile to look up — a UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            "out",
            kind = SlotKind.STRING,
            typeLabel = "HubSpot ID",
            description = "The profile's existing HubSpot object id — feed a HubSpot Update node.",
        ),
    ],
)
@Serializable
@SerialName("hubspotGetId")
class GetHubSpotIdNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val label = name.ifBlank { id }
        val profileId = GetHubSpotIdNodeSerializer.deserialize(context, inputs).profile
        val hubspotId = provide<ProfileService>().getAttributes(profileId)
            .getAttributeString("bosca.profiles.hubspot.id", "id")
            ?: error("Get HubSpot Id node '$label' found no HubSpot id on profile $profileId")
        return GetHubSpotIdNodeSerializer.serialize(hubspotId)
    }
}
