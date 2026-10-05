package bosca.profile.guide.graphql

import bosca.content.metadata.model.Guide
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.mark.model.ProfileMark
import bosca.profile.mark.service.ProfileMarkService
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext

@TypeController
class ProfileGuideProgressController(
    private val guideService: GuideService,
    private val markService: ProfileMarkService,
    private val metadataService: MetadataService,
    private val metadataPermissionEvaluator: MetadataPermissionEvaluator,
    private val profilePermissionEvaluator: ProfilePermissionEvaluator,
    private val profileService: ProfileService
) : GraphQLController<ProfileGuideProgress> {

    @Field
    fun attributes(progress: ProfileGuideProgress) = progress.attributes

    @Field
    fun completedStepIds(progress: ProfileGuideProgress) = progress.completedStepIds

    @Field
    fun version(progress: ProfileGuideProgress) = progress.version

    @Field
    suspend fun guide(
        authentication: AuthenticationContext,
        progress: ProfileGuideProgress,
    ): Guide? {
        val metadata = metadataService.getById(progress.metadataId) ?: return null
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
            return null
        }
        return guideService.getGuide(progress.metadataId, progress.version)
    }

    @Field
    suspend fun marks(
        authentication: AuthenticationContext,
        progress: ProfileGuideProgress
    ): List<ProfileMark> {
        val profile = profileService.getById(progress.profileId)
        if (!profilePermissionEvaluator.isAllowed(authentication, profile, PermissionAction.VIEW)) {
            return emptyList()
        }
        val steps = guideService.getGuideSteps(progress.metadataId, progress.version)
        val metadataIds = steps.mapNotNull { it.stepMetadataId }
        if (metadataIds.isEmpty()) return emptyList()
        return markService.getMarks(progress.profileId, metadataIds)
    }

    @Field
    suspend fun metadata(
        authentication: AuthenticationContext,
        progress: ProfileGuideProgress
    ): Metadata? {
        val metadata = metadataService.getById(progress.metadataId, progress.version) ?: return null
        if (!metadataPermissionEvaluator.isAllowed(authentication, metadata, PermissionAction.VIEW)) {
            return null
        }
        return metadata
    }

    @Field
    fun modified(progress: ProfileGuideProgress) = progress.modified

    @Field
    suspend fun nextStepId(progress: ProfileGuideProgress): Long? {
        val allStepIds = guideService.getGuideSteps(progress.metadataId, progress.version).map { it.id }
        val completions = mutableMapOf<Long, Boolean>()
        for (id in progress.completedStepIds) {
            completions[id] = true
        }
        var firstNotComplete: Long? = null
        var nextStepId: Long? = null
        for (stepId in allStepIds) {
            val sessionComplete = completions[stepId] ?: false
            if (!sessionComplete && firstNotComplete == null) {
                firstNotComplete = stepId
            }
            if (!sessionComplete && nextStepId == null) {
                nextStepId = stepId
            } else if (sessionComplete) {
                nextStepId = null
            }
        }
        return nextStepId ?: firstNotComplete
    }

    @Field
    suspend fun percentage(
        progress: ProfileGuideProgress
    ): Float {
        if (progress.completedStepIds.isEmpty()) return 0.0f
        val done = progress.completedStepIds.size.toFloat()
        val steps = guideService.getStepCount(progress.metadataId, progress.version).toFloat()
        if (steps == 0.0f) return 0.0f
        return (done / steps) * 100.0f
    }

    @Field
    fun started(progress: ProfileGuideProgress) = progress.started
}
