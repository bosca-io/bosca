package bosca.hubspot.transformer

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.security.model.Group
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.transformations.Transformation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class HubSpotMemberContext(
    val profile: Profile,
    val attributes: Map<String, ProfileAttribute>,
    val organization: Organization
)

@Serializable
data class HubSpotContext(
    val profile: Profile,
    val attributes: Map<String, ProfileAttribute>,
    val organization: Organization? = null,
    val memberships: List<HubSpotMemberContext>? = null,
    val principal: Principal? = null,
    val lastLogin: OffsetDateTime? = null,
    val groups: List<Group> = emptyList(),
)

class HubspotData(
    val id: UUID,
    val type: ProfileType,
    val hubspotId: String?,
    val data: Map<String, Any>,
    val context: JsonElement,
    val memberships: List<HubspotData>? = null
)

class HubSpotEntityTransformer(
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
    private val securityService: SecurityService,
    private val json: Json
) : Transformation<Unit, UUID, HubspotData> {

    override suspend fun transform(context: Unit, item: UUID): HubspotData {
        val profile = profileService.getById(item)
        val organization = if (profile.type == ProfileType.ORGANIZATION) {
            organizationService.getOrganizationByProfile(profile.id)
        } else {
            null
        }
        val context = HubSpotContext(
            profile,
            profileService.getAttributes(profile.id).associateBy { it.typeId },
            organization,
            if (profile.type == ProfileType.GENERIC && profile.principal != null) {
                organizationService.getMemberOrganizations(profile.principal ?: error("missing principal")).map {
                    val organization = organizationService.getOrganization(it.organizationId)
                    HubSpotMemberContext(
                        profileService.getById(organization.profileId),
                        profileService.getAttributes(organization.profileId).associateBy { it.typeId },
                        organization
                    )
                }
            } else {
                emptyList()
            },
            profile.principal?.let { securityService.getPrincipalById(it) },
            profile.principal?.let { securityService.getPrincipalLastLogin(it) },
            profile.principal?.let { securityService.getPrincipalGroups(it) } ?: emptyList(),
        )
        val hubspotId = context.attributes["bosca.profiles.hubspot.id"]?.attributes?.jsonObject?.get("id")?.jsonPrimitive?.content
        val ctx = json.encodeToJsonElement(context)

        @Suppress("UNCHECKED_CAST")
        val memberships = if (profile.type == ProfileType.GENERIC && context.memberships != null) {
            context.memberships.map { member ->
                val memberCtx = HubSpotContext(
                    member.profile,
                    member.attributes,
                    member.organization
                )
                val memberHubspotId = member.attributes["bosca.profiles.hubspot.id"]?.attributes?.jsonObject?.get("id")?.jsonPrimitive?.content
                val memberJsonCtx = json.encodeToJsonElement(memberCtx)
                @Suppress("UNCHECKED_CAST")
                HubspotData(
                    member.profile.id,
                    ProfileType.ORGANIZATION,
                    memberHubspotId,
                    memberJsonCtx.toAny() as Map<String, Any>,
                    memberJsonCtx
                )
            }
        } else {
            null
        }
        @Suppress("UNCHECKED_CAST")
        return HubspotData(
            profile.id,
            profile.type,
            hubspotId,
            ctx.toAny() as Map<String, Any>,
            ctx,
            memberships
        )
    }
}