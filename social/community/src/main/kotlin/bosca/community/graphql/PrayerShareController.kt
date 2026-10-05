package bosca.community.graphql

import bosca.community.model.PrayerShare
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class PrayerShareController(
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<PrayerShare> {

    @Field fun profileId(entry: PrayerShare) = entry.profileId
    @Field fun sharedAt(entry: PrayerShare) = entry.sharedAt

    @Field
    suspend fun profile(authentication: AuthenticationContext?, entry: PrayerShare): Profile? {
        val profile = profileService.getById(entry.profileId)
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) return null
        return profile
    }
}
