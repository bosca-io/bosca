package bosca.profile.guide.service

import bosca.content.metadata.service.GuideService
import bosca.content.metadata.model.GuideStep
import bosca.analytics.model.Content
import bosca.analytics.model.Element
import bosca.analytics.model.Event
import bosca.analytics.model.Events
import bosca.analytics.model.EventType
import bosca.analytics.server.ServerAnalyticsClient
import bosca.analytics.server.ServerEventContext
import bosca.analytics.server.analyticsContext
import bosca.db.afterCommit
import bosca.db.transaction
import bosca.profile.guide.events.ProfileGuideCompleted
import bosca.profile.guide.events.ProfileGuideProgressAdded
import bosca.profile.guide.events.ProfileGuideProgressDeleted
import bosca.profile.guide.events.dispatch
import bosca.profile.guide.model.GuideProgressStatistics
import bosca.profile.guide.model.ProfileGuideHistory
import bosca.profile.guide.model.ProfileGuideProgress
import bosca.profile.guide.repository.ProfileGuideHistoryRepository
import bosca.profile.guide.repository.ProfileGuideProgressRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@ServiceImplementation
class ProfileGuideServiceImpl(
    private val progressRepository: ProfileGuideProgressRepository,
    private val historyRepository: ProfileGuideHistoryRepository,
    private val guideService: GuideService,
    private val analytics: ServerAnalyticsClient,
) : ProfileGuideService {

    override suspend fun getAllProgress(profileId: UUID, limit: Int, offset: Long): List<ProfileGuideProgress> {
        return progressRepository.findByProfileId(profileId, limit, offset)
    }

    override suspend fun getAllProgress(
        profileId: UUID,
        metadataId: UUID,
        limit: Int,
        offset: Long,
    ): List<ProfileGuideProgress> {
        return progressRepository.findByProfileAndMetadataId(profileId, metadataId, limit, offset)
    }

    override suspend fun getProgressCount(profileId: UUID): Long {
        return progressRepository.countByProfileId(profileId)
    }

    override suspend fun getProgressCount(profileId: UUID, metadataId: UUID): Long {
        return progressRepository.countByProfileAndMetadataId(profileId, metadataId)
    }

    override suspend fun getProgress(profileId: UUID, metadataId: UUID, version: Int): ProfileGuideProgress? {
        return progressRepository.findByProfileAndMetadata(profileId, metadataId, version)
    }

    override suspend fun getStatistics(metadataId: UUID): GuideProgressStatistics {
        return progressRepository.statistics(metadataId)
    }

    override suspend fun getActiveProfileIds(
        metadataId: UUID,
        limit: Int,
        offset: Long,
    ): List<UUID> {
        return progressRepository.findProfileIdsByMetadataId(metadataId, limit, offset)
    }

    override suspend fun getActiveProgress(
        metadataId: UUID,
        profileIds: List<UUID>,
    ): List<ProfileGuideProgress> {
        if (profileIds.isEmpty()) return emptyList()
        return progressRepository.findByMetadataIdAndProfileIds(metadataId, profileIds)
    }

    override suspend fun getAllHistory(profileId: UUID, limit: Int, offset: Long): List<ProfileGuideHistory> {
        return historyRepository.findByProfileId(profileId, limit, offset)
    }

    override suspend fun getHistoryCount(profileId: UUID): Long {
        return historyRepository.countByProfileId(profileId)
    }

    override suspend fun getHistory(
        profileId: UUID,
        metadataId: UUID,
        version: Int,
        limit: Int,
        offset: Long
    ): List<ProfileGuideHistory> {
        return historyRepository.findByProfileAndMetadata(profileId, metadataId, version, limit, offset)
    }

    override suspend fun getHistoryCount(profileId: UUID, metadataId: UUID, version: Int): Long {
        return historyRepository.countByProfileAndMetadata(profileId, metadataId, version)
    }

    override suspend fun addProgress(
        profileId: UUID,
        metadataId: UUID,
        metadataVersion: Int,
        stepId: Long,
        attributes: JsonElement?
    ): ProfileGuideProgress {
        return transaction {
            val steps = guideService.getGuideSteps(metadataId, metadataVersion)
            val stepIds = steps.map { it.id }
            val attr = attributes ?: JsonObject(emptyMap())
            val existing = progressRepository.getProgressForUpdate(profileId, metadataId, metadataVersion)
            val current = existing ?: ProfileGuideProgress(
                profileId = profileId,
                metadataId = metadataId,
                version = metadataVersion,
                attributes = attributes ?: JsonObject(emptyMap()),
            )
            val hasStepId = stepIds.contains(stepId)
            val progress = if (hasStepId) {
                progressRepository.addStepProgress(profileId, metadataId, metadataVersion, stepId, attr)
            } else {
                progressRepository.addProgress(profileId, metadataId, metadataVersion, attr)
            } ?: run {
                // Null means the step was already recorded (conditional upsert matched nothing) —
                // nothing changed, so no ProgressAdded event.
                return@transaction current.copy(
                    attributes = current.attributes?.takeIf { it != JsonNull }?.let { JsonObject(it.jsonObject + attr.jsonObject) } ?: attr,
                    completedStepIds = if (stepId != 0L) current.completedStepIds + stepId else current.completedStepIds
                )
            }
            if (progress.completedStepIds.size == stepIds.size) {
                historyRepository.add(profileId, metadataId, metadataVersion, progress.attributes)
                progressRepository.delete(profileId, metadataId, metadataVersion)
                ProfileGuideCompleted(profileId, metadataId, metadataVersion).dispatch()
            }
            if (hasStepId) {
                val completion = completionEvents(progress, steps.first { it.id == stepId },
                    guideCompleted = progress.completedStepIds.size == stepIds.size)
                afterCommit { analytics.capture(completion) }
            }
            val previousCompletedStepCount = existing?.completedStepIds?.size ?: 0
            val completedStepCount = progress.completedStepIds.size
            val totalStepCount = stepIds.size
            val percentage = if (totalStepCount == 0) {
                0.0
            } else {
                completedStepCount.toDouble() * 100.0 / totalStepCount
            }
            val percentageMilestone = if (totalStepCount == 0) {
                0
            } else {
                ((completedStepCount.toLong() * 100 / totalStepCount) / 10 * 10).toInt()
            }
            val previousPercentageMilestone = if (totalStepCount == 0) {
                0
            } else {
                ((previousCompletedStepCount.toLong() * 100 / totalStepCount) / 10 * 10).toInt()
            }
            ProfileGuideProgressAdded(
                profileId = profileId,
                metadataId = metadataId,
                metadataVersion = metadataVersion,
                stepId = stepId,
                initialProgress = existing == null,
                percentage = percentage,
                percentageMilestone = percentageMilestone,
                firstAtPercentageMilestone = percentageMilestone > previousPercentageMilestone,
            ).dispatch()
            progress
        }
    }

    override suspend fun deleteProgress(profileId: UUID, metadataId: UUID, metadataVersion: Int) {
        progressRepository.delete(profileId, metadataId, metadataVersion)
        ProfileGuideProgressDeleted(profileId, metadataId, metadataVersion).dispatch()
    }

    /** Preserve the saved occurrence time and stable identities across duplicate event delivery. */
    private suspend fun completionEvents(progress: ProfileGuideProgress, step: GuideStep, guideCompleted: Boolean): Events {
        val context = analyticsContext()
        val occurred = progress.modified.toInstant()
        val sent = Instant.now()
        val cycle = "guide:${progress.profileId}:${progress.metadataId}:${progress.version}:${progress.started}"
        val extras = buildJsonObject {
            put("guide_id", progress.metadataId.toString())
            put("guide_version", progress.version)
            put("guide_started", progress.started.toString())
            put("guide_step_id", step.id)
        }
        val parent = Content(progress.metadataId.toString(), "Metadata")
        val stepContents = listOfNotNull(parent, step.stepMetadataId?.let { Content(it.toString(), "Metadata") })
            .distinctBy { it.id }
        fun event(id: String, kind: String, content: List<Content>) = Event(
            created = occurred.toEpochMilli(),
            createdMicros = ((occurred.nano / 1000) % 1000).toLong(),
            clientId = id,
            type = EventType.Completion,
            element = Element(id = id, type = kind, content = content, extras = extras),
        )
        return Events(
            context = ServerEventContext.build(
                appId = context.appId.orEmpty(),
                installationId = context.installationId.orEmpty(),
                sessionId = context.sessionId,
                userId = progress.profileId.toString(),
            ).copy(appVersion = context.appVersion.orEmpty()),
            events = buildList {
                add(event("$cycle:step:${step.id}", "guide_step", stepContents))
                if (guideCompleted) add(event("$cycle:complete", "guide", listOf(parent)))
            },
            sent = sent.toEpochMilli(),
            sentMicros = ((sent.nano / 1000) % 1000).toLong(),
        )
    }
}
