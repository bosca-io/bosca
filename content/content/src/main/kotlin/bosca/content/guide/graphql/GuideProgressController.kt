package bosca.content.guide.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.guide.model.GuideProgressStatistics
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.service.ProfileGuideService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Global content-guide queries. */
object Guides

/** Administrator-facing progression data for one guide across all of its versions. */
@Serializable
class GuideProgress(
    @Contextual
    val metadataId: UUID,
)

/** One profile and its active progress records for the selected guide. */
@Serializable
class GuideProgressProfile(
    val profile: Profile,
    val progressions: List<ProfileGuideProgress>,
)

@TypeController
class GuidesController(
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<Guides> {

    @Field
    fun progress(
        authentication: AuthenticationContext,
        id: UUID,
    ): GuideProgress {
        groupEvaluator.verifyHasSaGroup(authentication)
        return GuideProgress(id)
    }
}

@TypeController
class GuideProgressController(
    private val service: ProfileGuideService,
    private val profileService: ProfileService,
    private val groupEvaluator: GroupEvaluator,
) : GraphQLController<GuideProgress> {

    @Field
    suspend fun statistics(
        authentication: AuthenticationContext,
        progress: GuideProgress,
    ): GuideProgressStatistics {
        groupEvaluator.verifyHasSaGroup(authentication)
        return service.getStatistics(progress.metadataId)
    }

    @Field
    suspend fun activeProfiles(
        authentication: AuthenticationContext,
        progress: GuideProgress,
        limit: Int,
        offset: Long,
    ): List<GuideProgressProfile> {
        groupEvaluator.verifyHasSaGroup(authentication)
        val profileIds = service.getActiveProfileIds(
            progress.metadataId,
            limit.coerceIn(1, 100),
            offset.coerceAtLeast(0),
        )
        if (profileIds.isEmpty()) return emptyList()
        val profilesById = profileService.getAllByIds(profileIds).associateBy { it.id }
        val progressByProfileId = service.getActiveProgress(progress.metadataId, profileIds).groupBy { it.profileId }
        return profileIds.mapNotNull { profileId ->
            profilesById[profileId]?.let { profile ->
                GuideProgressProfile(profile, progressByProfileId[profileId].orEmpty())
            }
        }
    }
}

@TypeController
class GuideProgressProfileController : GraphQLController<GuideProgressProfile> {

    @Field
    fun profile(progress: GuideProgressProfile) = progress.profile

    @Field
    fun progressions(progress: GuideProgressProfile) = progress.progressions
}

@TypeController
class GuideProgressStatisticsController : GraphQLController<GuideProgressStatistics> {

    @Field
    fun activeProgressions(statistics: GuideProgressStatistics) = statistics.activeProgressions

    @Field
    fun activeProfiles(statistics: GuideProgressStatistics) = statistics.activeProfiles

    @Field
    fun historicalProgressions(statistics: GuideProgressStatistics) = statistics.historicalProgressions

    @Field
    fun completions(statistics: GuideProgressStatistics) = statistics.completions

    @Field
    fun totalProgressions(statistics: GuideProgressStatistics) = statistics.totalProgressions

    @Field
    fun uniqueProfiles(statistics: GuideProgressStatistics) = statistics.uniqueProfiles
}
