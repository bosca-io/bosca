package bosca.profile.rating.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.rating.model.ProfileRating
import bosca.profile.rating.service.ProfileRatingService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID

object ProfileRatings

@TypeController
class ProfileRatingsController(
    private val service: ProfileRatingService,
    private val permissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<ProfileRatings> {

    @Field
    suspend fun ratings(
        authentication: AuthenticationContext,
        profile: Profile,
        limit: Int? = 10,
        offset: Int? = 0
    ): List<ProfileRating> {
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return service.getRatingsByProfile(profile.id).drop(offset ?: 0)
            .take(limit ?: 10)
    }

    @Field
    suspend fun count(authentication: AuthenticationContext, profile: Profile): Long {
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return service.getRatingsByProfile(profile.id).size.toLong()
    }

    @Field
    suspend fun rating(
        authentication: AuthenticationContext,
        profile: Profile,
        collectionId: UUID? = null,
        metadataId: UUID? = null,
        metadataVersion: Int? = null
    ): ProfileRating? {
        permissionEvaluator.verifyAllowed(authentication, profile, PermissionAction.VIEW)
        return when {
            metadataId != null && metadataVersion != null -> {
                service.getRating(profile.id, metadataId, metadataVersion)
            }

            collectionId != null -> {
                service.getRating(profile.id, collectionId)
            }

            else -> null
        }
    }
}
