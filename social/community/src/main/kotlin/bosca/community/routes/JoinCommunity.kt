package bosca.community.routes

import bosca.community.service.CommunityService
import bosca.di.ObjectProvider
import bosca.profile.organization.service.OrganizationService
import bosca.profile.organization.service.process
import bosca.profile.profile.service.ProfileService
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.security.model.SignupToken
import bosca.security.model.SignupTokenType
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/community/join")
class JoinCommunity(
    private val profileService: ProfileService,
    private val organizationService: OrganizationService,
    private val communityService: ObjectProvider<CommunityService>
) : Route<Unit>() {

    override fun serializer(): KSerializer<Unit>? = null

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val principal = authenticationContext.principal()?.asPrincipal() ?: error("Missing principal")
        val token = call.request.queryParameters["token"] ?: error("Missing token")
        val profiles = profileService.getByPrincipal(authenticationContext.principal()?.id ?: error("Missing principal id"))
        val profile = profiles.find { it.isPrimary } ?: profiles.firstOrNull() ?: error("No profile found")
        listOf(SignupToken(SignupTokenType.COMMUNITY_GROUP, token)).process(
            profile,
            principal,
            profileService,
            organizationService,
            communityService
        )
    }
}