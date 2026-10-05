package bosca.content.metadata.graphql

import bosca.content.metadata.model.MetadataProfile
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class MetadataProfileController(
    private val service: ProfileService,
    private val permissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<MetadataProfile> {

    @Field
    suspend fun profile(authentication: AuthenticationContext, profile: MetadataProfile): Profile? {
        val profile = service.getById(profile.profileId)
        if (permissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
            return profile
        }
        return null
    }

    @Field
    fun relationship(profile: MetadataProfile) = profile.relationship
}