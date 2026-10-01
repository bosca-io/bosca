package bosca.community.graphql

import bosca.community.model.PrayerComment
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
class PrayerCommentController(
    private val profileService: ProfileService,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val prayerService: PrayerService,
) : GraphQLController<PrayerComment> {

    @Field fun id(comment: PrayerComment) = comment.id
    @Field fun prayerId(comment: PrayerComment) = comment.prayerId
    @Field fun parentId(comment: PrayerComment) = comment.parentId
    @Field fun profileId(comment: PrayerComment) = comment.profileId
    @Field fun content(comment: PrayerComment) = comment.content
    @Field fun created(comment: PrayerComment) = comment.created
    @Field fun attributes(comment: PrayerComment) = comment.attributes

    @Field
    suspend fun profile(authentication: AuthenticationContext?, comment: PrayerComment): Profile? {
        val profile = profileService.getById(comment.profileId)
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) return null
        return profile
    }

    @Field
    suspend fun replies(
        authentication: AuthenticationContext?,
        comment: PrayerComment,
        limit: Int = 50,
        offset: Int = 0
    ): List<PrayerComment> {
        return prayerService.getReplies(comment.id, limit, offset)
    }
}
