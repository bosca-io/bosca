package bosca.community.graphql

import bosca.community.model.PrayedByEntry
import bosca.community.model.Prayer
import bosca.community.model.PrayerAnniversary
import bosca.community.model.PrayerComment
import bosca.community.model.PrayerLike
import bosca.community.model.PrayerShare
import bosca.community.service.PrayerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class PrayerController(
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val prayerService: PrayerService,
) : GraphQLController<Prayer> {

    @Field fun id(prayer: Prayer) = prayer.id
    @Field fun profileId(prayer: Prayer) = prayer.profileId
    @Field fun title(prayer: Prayer) = prayer.title
    @Field fun content(prayer: Prayer) = prayer.content
    @Field fun status(prayer: Prayer) = prayer.status
    @Field fun created(prayer: Prayer) = prayer.created
    @Field fun modified(prayer: Prayer) = prayer.modified
    @Field fun answeredAt(prayer: Prayer) = prayer.answeredAt
    @Field fun lastActivityAt(prayer: Prayer) = prayer.lastActivityAt
    @Field fun prayerActionCount(prayer: Prayer) = prayer.prayerActionCount
    @Field fun likeCount(prayer: Prayer) = prayer.likeCount
    @Field fun commentCount(prayer: Prayer) = prayer.commentCount
    @Field fun suppressAnniversaries(prayer: Prayer) = prayer.suppressAnniversaries
    @Field fun attributes(prayer: Prayer) = prayer.attributes

    @Field
    suspend fun profile(authentication: AuthenticationContext?, prayer: Prayer): Profile? {
        val profile = profileService.getById(prayer.profileId)
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) return null
        return profile
    }

    @Field
    suspend fun prayedByMe(authentication: AuthenticationContext?, prayer: Prayer): Boolean {
        val principal = authentication?.principal() ?: return false
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return false
        return prayerService.hasPrayed(prayer.id, profile.id)
    }

    @Field
    suspend fun prayedBy(
        authentication: AuthenticationContext?,
        prayer: Prayer,
        limit: Int = 50,
        offset: Int = 0
    ): List<PrayedByEntry> {
        return prayerService.getPrayedBy(prayer.id, limit, offset)
    }

    @Field
    suspend fun likedByMe(authentication: AuthenticationContext?, prayer: Prayer): Boolean {
        val principal = authentication?.principal() ?: return false
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return false
        return prayerService.hasLiked(prayer.id, profile.id)
    }

    @Field
    suspend fun likes(
        authentication: AuthenticationContext?,
        prayer: Prayer,
        limit: Int = 50,
        offset: Int = 0
    ): List<PrayerLike> {
        return prayerService.getLikes(prayer.id, limit, offset)
    }

    @Field
    suspend fun comments(
        authentication: AuthenticationContext?,
        prayer: Prayer,
        limit: Int = 50,
        offset: Int = 0
    ): List<PrayerComment> {
        return prayerService.getComments(prayer.id, limit, offset)
    }

    @Field
    suspend fun anniversaries(authentication: AuthenticationContext?, prayer: Prayer): List<PrayerAnniversary> {
        return prayerService.getAnniversaries(prayer.id)
    }

    @Field
    suspend fun shares(authentication: AuthenticationContext?, prayer: Prayer): List<PrayerShare> {
        val principal = authentication?.principal() ?: return emptyList()
        val profile = profileService.getByPrincipal(principal.id).firstOrNull() ?: return emptyList()
        if (profile.id != prayer.profileId) return emptyList()
        return prayerService.getShares(prayer.id)
    }
}
