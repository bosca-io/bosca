package bosca.hubspot.pipeline

import bosca.di.provide
import bosca.hubspot.client.HubSpot
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.profile.attribute.model.getAttributeString
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Action contributed by `integrations/hubspot`: remove the HubSpot contact→company association for a
 * member leaving an organization — the work the legacy `RemoveMemberFromHubSpotJob` performs on a
 * membership-removed event. Reads the organization id on `organization` and the member's principal id
 * on `member`, resolves both HubSpot ids, and removes the association. [willSuspend] makes it durable.
 *
 * A no-op when either side has no HubSpot id (nothing to disassociate), matching the legacy job.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Remove HubSpot Member",
    description = "Remove the association between a member's HubSpot contact and their organization's company.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "organization", kind = SlotKind.UUID, typeLabel = "Organization id", description = "The organization — a UUID."),
        InputSlot(name = "member", kind = SlotKind.UUID, typeLabel = "Member principal id", description = "The member's principal — a UUID."),
    ],
)
@Serializable
@SerialName("hubspotRemoveMember")
class RemoveHubSpotMemberNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure messages layered on top.
        val label = name.ifBlank { id }
        val input = RemoveHubSpotMemberNodeSerializer.deserializePartial(context, inputs)
        val organizationId = input.organization
            ?: error("Remove HubSpot Member node '$label' requires an organization UUID on its 'organization' input")
        val memberId = input.member
            ?: error("Remove HubSpot Member node '$label' requires a member principal UUID on its 'member' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "removeHubSpotMember")
            put("organization", organizationId.toString())
            put("member", memberId.toString())
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = RemoveHubSpotMemberNodeSerializer.deserialize(context, inputs)
        val organizationId = input.organization
        val memberId = input.member
        val organizationProfileId = provide<OrganizationService>().getOrganization(organizationId).profileId
        val organizationHubspotId = hubspotId(organizationProfileId)
        val memberProfile = provide<ProfileService>().getByPrincipal(memberId).firstOrNull()
        val memberHubspotId = memberProfile?.let { hubspotId(it.id) }
        // Nothing synced to disassociate — nothing to do (the legacy job returns here too).
        if (organizationHubspotId == null || memberHubspotId == null) return null
        provide<HubSpot>().removeAssociation(
            fromObjectType = "contact",
            fromObjectId = memberHubspotId,
            toObjectType = "company",
            toObjectId = organizationHubspotId,
        )
        return null
    }

    private suspend fun hubspotId(profileId: UUID): String? =
        provide<ProfileService>().getAttributes(profileId).getAttributeString("bosca.profiles.hubspot.id", "id")
}
