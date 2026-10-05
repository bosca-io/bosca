package bosca.profile.organization.routes

import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.organization.model.Organization
import bosca.profile.organization.model.OrganizationPermission
import bosca.profile.organization.model.OrganizationSignupToken
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.OrganizationPermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull

@Serializable
class OrganizationProfile(
    val organization: Organization,
    val profile: Profile,
    val permissions: List<OrganizationPermission>,
    val signupTokens: List<OrganizationSignupToken>,
    val profileAttributes: List<ProfileAttribute>
)

@RouteController("/api/v1/organizations/{id}")
class OrganizationById(
    private val organizationService: OrganizationService,
    private val profileService: ProfileService,
    private val organizationPermissionEvaluator: OrganizationPermissionEvaluator
) : Route<OrganizationProfile>() {

    override fun serializer(): KSerializer<OrganizationProfile> = OrganizationProfile.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): OrganizationProfile {
        val id = UUID.parse(call.pathParameters["id"] ?: error("missing id"))
        val organization = organizationService.getOrganization(id)
        organizationPermissionEvaluator.verifyAllowed(authenticationContext, organization, PermissionAction.VIEW)
        val canManage = organizationPermissionEvaluator.isAllowed(authenticationContext, organization, PermissionAction.MANAGE)
        return OrganizationProfile(
            organization.copy(systemAttributes = JsonNull),
            profileService.getById(organization.profileId),
            if (canManage) organizationService.getPermissions(organization.id).map { it as OrganizationPermission } else emptyList(),
            if (canManage) organizationService.getSignupTokens(organization.id) else emptyList(),
            profileService.getAttributes(organization.profileId)
        )
    }
}