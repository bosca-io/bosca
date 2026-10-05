package bosca.profile.relationship.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.relationship.model.ProfileRelationship
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import kotlinx.serialization.json.JsonElement

/** Field resolver for the ProfileRelationship GraphQL type. */
@TypeController(type = "ProfileRelationship")
class ProfileRelationshipController(
    private val profileService: ProfileService,
    private val permissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<ProfileRelationship> {

    @Field
    suspend fun profile(
        authentication: AuthenticationContext,
        relationship: ProfileRelationship,
    ): Profile {
        val profile = profileService.getById(relationship.profileId2)
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return profile
    }

    @Field
    fun type(relationship: ProfileRelationship): String = relationship.type

    @Field
    fun attributes(relationship: ProfileRelationship): JsonElement? = relationship.attributes
}
