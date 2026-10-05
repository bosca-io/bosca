package bosca.profile.profile.routes

import bosca.profile.model.Profile
import bosca.profile.profile.model.ProfileInput
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.routes.Route
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.server.ServerCall
import kotlinx.serialization.KSerializer

@RouteController("/api/v1/profiles/{id}", method = RouteMethod.POST)
class EditProfile(
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
) : Route<Profile>() {

    override fun serializer(): KSerializer<Profile> = Profile.serializer()

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): Profile {
        val profileId = UUID.parse(call.pathParameters["id"] ?: error("invalid profile id"))
        val profile = profileService.getById(profileId)
        profilePermissionEvaluator.verifyAllowed(authenticationContext, profile, PermissionAction.EDIT)
        val profileInput = call.receive<ProfileInput>()
        return profileService.edit(profile.id, profileInput)
    }
}