package bosca.hubspot.pipeline

import bosca.di.provide
import bosca.hubspot.client.HubSpot
import bosca.hubspot.transformer.HubSpotEntityTransformer
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action contributed by `integrations/hubspot`: create the profile's HubSpot contact→company
 * associations from its memberships. Reads the profile [bosca.serialization.UUID] on `profile`,
 * resolves the profile's already-written HubSpot contact and associates it with each membership's
 * company (the same step the legacy sync job performed, via
 * [bosca.hubspot.client.HubSpot.createAssociations]). [willSuspend] makes it durable: in a durable run
 * the associate runs inside a child of the run job (retried, its outcome reflected on the run) rather
 * than fire-and-forget; the node emits a receipt downstream.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Associate HubSpot Memberships",
    description = "Create HubSpot contact→company associations from a profile's memberships.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "profile", kind = SlotKind.UUID, typeLabel = "Profile id", description = "The profile whose memberships to associate — a UUID."),
    ],
    outputs = [
        OutputSlot(name = "profile", kind = SlotKind.UUID),
    ]
)
@Serializable
@SerialName("hubspotAssociateMemberships")
class AssociateHubSpotMembershipsNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure message layered on top.
        val profileId = AssociateHubSpotMembershipsNodeSerializer.deserializePartial(context, inputs).profile
            ?: error("Associate HubSpot Memberships node '${name.ifBlank { id }}' requires a profile UUID on its 'profile' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "associateHubSpotMemberships")
            put("profileId", profileId.toString())
        })
        return inputs.first
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val profileId = AssociateHubSpotMembershipsNodeSerializer.deserialize(context, inputs).profile
        val profile = provide<HubSpotEntityTransformer>().transform(Unit, profileId)
        provide<HubSpot>().createAssociations(profile)
        return inputs.first
    }
}
