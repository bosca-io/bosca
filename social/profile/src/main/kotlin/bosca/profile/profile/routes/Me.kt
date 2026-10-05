package bosca.profile.profile.routes

import bosca.di.ObjectProvider
import bosca.profile.attribute.model.ProfileAttribute
import bosca.profile.model.Profile
import bosca.profile.model.ProfileVisibility
import bosca.profile.organization.model.Organization
import bosca.profile.organization.service.OrganizationService
import bosca.profile.profile.service.ProfileService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

@Serializable
class ProfileOrganization(
    val organization: Organization,
    val profile: Profile,
    val profileAttributes: List<ProfileAttribute>
)

@Serializable
class ProfileResponse(
    val profile: Profile,
    val attributes: List<ProfileAttribute>,
    val organizations: List<ProfileOrganization>,
    val editor: Boolean? = null
)

@RouteController("/api/v1/profiles/me")
class Me(
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
    private val groupEvaluator: ObjectProvider<GroupEvaluator>
) : Route<List<ProfileResponse>>() {

    override fun serializer(): KSerializer<List<ProfileResponse>> = ListSerializer(ProfileResponse.serializer())

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): List<ProfileResponse> {
        val principalId = authenticationContext.principal()?.id ?: throw SecurityException()
        val profiles = profileService.getByPrincipal(principalId)
        val isEditor = call.request.queryParameters["isEditor"]?.toBoolean() ?: false
        return profiles.map {
            ProfileResponse(
                it,
                profileService.getAttributes(it.id).filter { it.visibility != ProfileVisibility.SYSTEM },
                organizationService.getMemberOrganizations(principalId).map {
                    val organization = organizationService.getOrganization(it.organizationId).copy(
                        systemAttributes = JsonNull
                    )
                    ProfileOrganization(
                        organization,
                        profileService.getById(organization.profileId),
                        profileService.getAttributes(organization.profileId).filter { it.visibility != ProfileVisibility.SYSTEM }
                    )
                },
                editor = if (isEditor) {
                    groupEvaluator.get().hasEditorGroup(authenticationContext)
                } else {
                    null
                }
            )
        }
    }
}