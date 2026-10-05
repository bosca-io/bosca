package bosca.community.graphql

import bosca.community.model.PrayerLike
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class PrayerLikeController(
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<PrayerLike> {

    @Field fun profileId(entry: PrayerLike) = entry.profileId
    @Field fun likedAt(entry: PrayerLike) = entry.likedAt

    @Field
    suspend fun profile(authentication: AuthenticationContext?, entry: PrayerLike): Profile? {
        val profile = profileService.getById(entry.profileId)
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) return null
        return profile
    }
}
