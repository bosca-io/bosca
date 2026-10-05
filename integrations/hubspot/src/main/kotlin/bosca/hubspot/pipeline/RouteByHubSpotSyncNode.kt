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
 * Router contributed by `integrations/hubspot`: branch a profile sync on whether the profile is
 * already in HubSpot. Reads the profile [bosca.serialization.UUID] on `profile`, looks up the
 * `bosca.profiles.hubspot.id` attribute a prior sync stamped, and **passes the profile id through**
 * on one of two ports:
 *  - `synced` — the profile already has a HubSpot object; drive the update path
 *    ([CreateHubSpotPropertiesNode] + [GetHubSpotIdNode] → [HubSpotUpdateNode]),
 *  - `unsynced` — the profile has none yet; drive the add path
 *    ([CreateHubSpotPropertiesNode] → [HubSpotAddNode] → [AddHubSpotProfileAttributeNode]).
 *
 * This is the add-vs-update branch the monolithic legacy sync made on `profile.hubspotId`. It emits
 * the **profile id** (not the HubSpot id) on both ports so each branch can recompute the HubSpot
 * properties for itself; the update branch pairs with [GetHubSpotIdNode] to resolve the id. A pure
 * read with no side effect, so it runs unchanged in a dry run.
 *
 * Contributed to the engine purely by the `@PipelineNodeType` annotation — no engine changes.
 */
@PipelineNodeType(
    category = NodeCategory.TRANSFORM,
    label = "Route by HubSpot Sync State",
    description = "Branch a profile sync on whether it already exists in HubSpot.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(
            name = "profile",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile to route — a UUID.",
        ),
    ],
    outputs = [
        OutputSlot(
            "synced",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile already has a HubSpot object — drive the update path.",
        ),
        OutputSlot(
            "unsynced",
            kind = SlotKind.UUID,
            typeLabel = "Profile id",
            description = "The profile has no HubSpot object yet — drive the add path.",
        ),
    ],
)
@Serializable
@SerialName("hubspotSyncRoute")
class RouteByHubSpotSyncNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : TransformNode() {

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val profileId = RouteByHubSpotSyncNodeSerializer.deserialize(context, inputs).profile
        val hubspotId = provide<ProfileService>().getAttributes(profileId)
            .getAttributeString("bosca.profiles.hubspot.id", "id")
        return if (hubspotId != null) {
            RouteByHubSpotSyncNodeSerializer.serializeSynced(profileId)
        } else {
            RouteByHubSpotSyncNodeSerializer.serializeUnsynced(profileId)
        }
    }
}
