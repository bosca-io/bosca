package bosca.hubspot.pipeline

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.di.provide
import bosca.hubspot.client.HubSpot
import bosca.hubspot.configuration.HubSpotConfiguration
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
 * Action contributed by `integrations/hubspot`: create the HubSpot contact→company association for a
 * member joining an organization — the work the legacy `AddMemberToHubSpotJob` performs on a
 * membership-added event. Reads the organization id on `organization` and the member's principal id on
 * `member`, resolves both already-synced HubSpot ids (the member's contact and the organization's
 * company), and associates them using the configured association type. [willSuspend] makes it durable.
 *
 * Errors when either side has no HubSpot id yet, so the run retries until both have synced.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Associate HubSpot Member",
    description = "Associate a member's HubSpot contact with their organization's HubSpot company.",
    group = "Integrations",
    subgroup = "HubSpot",
    inputs = [
        InputSlot(name = "organization", kind = SlotKind.UUID, typeLabel = "Organization id", description = "The organization — a UUID."),
        InputSlot(name = "member", kind = SlotKind.UUID, typeLabel = "Member principal id", description = "The member's principal — a UUID."),
    ],
)
@Serializable
@SerialName("hubspotAssociateMember")
class AssociateHubSpotMemberNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    override val willSuspend: Boolean = true

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        // Lenient decode, with this node's own per-port failure messages layered on top.
        val label = name.ifBlank { id }
        val input = AssociateHubSpotMemberNodeSerializer.deserializePartial(context, inputs)
        val organizationId = input.organization
            ?: error("Associate HubSpot Member node '$label' requires an organization UUID on its 'organization' input")
        val memberId = input.member
            ?: error("Associate HubSpot Member node '$label' requires a member principal UUID on its 'member' input")
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "associateHubSpotMember")
            put("organization", organizationId.toString())
            put("member", memberId.toString())
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = AssociateHubSpotMemberNodeSerializer.deserialize(context, inputs)
        val organizationId = input.organization
        val memberId = input.member
        val configuration = provide<ConfigurationService>().getValueAs<HubSpotConfiguration>("hubspot", context.json)
            ?: error("Hubspot configuration not found")
        val organizationProfileId = provide<OrganizationService>().getOrganization(organizationId).profileId
        val organizationHubspotId = hubspotId(organizationProfileId)
            ?: error("Organization $organizationId has no HubSpot company id yet")
        val memberProfile = provide<ProfileService>().getByPrincipal(memberId).firstOrNull()
            ?: error("No profile for member principal $memberId")
        val memberHubspotId = hubspotId(memberProfile.id)
            ?: error("Member $memberId has no HubSpot contact id yet")
        provide<HubSpot>().createAssociation(
            fromObjectType = "contact",
            fromObjectId = memberHubspotId,
            toObjectType = "company",
            toObjectId = organizationHubspotId,
            associationTypeId = configuration.contactToCompanyAssociationTypeId,
            associationCategory = configuration.contactToCompanyAssociationCategory,
        )
        return null
    }

    private suspend fun hubspotId(profileId: UUID): String? =
        provide<ProfileService>().getAttributes(profileId).getAttributeString("bosca.profiles.hubspot.id", "id")
}
