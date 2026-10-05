package bosca.profile.guide.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Serializable


@Serializable
class ProfileGuideProgressions(val profile: Profile)

@TypeController
class ProfileGuideProgressionsController(
    private val service: ProfileGuideService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<ProfileGuideProgressions> {

    private fun canViewProgressions(authentication: AuthenticationContext, profile: Profile): Boolean {
        if (groupEvaluator.hasSaGroup(authentication)) {
            return true
        }
        val principal = authentication.principal()
        return profile.principal != null && profile.principal == principal?.id
    }

    @Field
    suspend fun all(
        authentication: AuthenticationContext,
        progressions: ProfileGuideProgressions,
        limit: Int,
        offset: Long,
        metadataId: UUID? = null,
    ): List<ProfileGuideProgress> {
        val profile = progressions.profile
        if (!canViewProgressions(authentication, profile)) {
            return emptyList()
        }
        return if (metadataId == null) {
            service.getAllProgress(profile.id, limit, offset)
        } else {
            service.getAllProgress(profile.id, metadataId, limit, offset)
        }
    }

    @Field
    suspend fun count(
        authentication: AuthenticationContext,
        progressions: ProfileGuideProgressions,
        metadataId: UUID? = null,
    ): Long {
        val profile = progressions.profile
        if (!canViewProgressions(authentication, profile)) {
            return 0L
        }
        return if (metadataId == null) {
            service.getProgressCount(profile.id)
        } else {
            service.getProgressCount(profile.id, metadataId)
        }
    }

    @Field
    suspend fun progress(
        authentication: AuthenticationContext,
        progressions: ProfileGuideProgressions,
        id: UUID,
        version: Int
    ): ProfileGuideProgress? {
        val profile = progressions.profile
        if (!canViewProgressions(authentication, profile)) {
            return null
        }
        return service.getProgress(profile.id, id, version)
    }
}
