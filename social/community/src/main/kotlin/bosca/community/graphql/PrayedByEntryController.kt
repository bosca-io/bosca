package bosca.community.graphql

import bosca.community.model.PrayedByEntry
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class PrayedByEntryController(
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
) : GraphQLController<PrayedByEntry> {

    @Field fun profileId(entry: PrayedByEntry) = entry.profileId
    @Field fun prayedAt(entry: PrayedByEntry) = entry.prayedAt

    @Field
    suspend fun profile(authentication: AuthenticationContext?, entry: PrayedByEntry): Profile? {
        val profile = profileService.getById(entry.profileId)
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) return null
        return profile
    }
}
