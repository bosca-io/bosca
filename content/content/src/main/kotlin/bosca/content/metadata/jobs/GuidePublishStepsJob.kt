package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.GuideService
import bosca.content.metadata.service.MetadataService
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

@Serializable
data class GuidePublishStepsJob(
    val id: UUID,
    val version: Int
) : IJobDefinition

@JobDefinition(GuidePublishStepsJob::class, JobQueueNames.contentJobQueue, "guide-publish-steps")
class GuidePublishStepsExecutor(
    private val metadataService: MetadataService,
    private val guideService: GuideService,
    private val transitioner: Transitioner,
    private val securityService: SecurityService,
) : AbstractJobExecutor<GuidePublishStepsJob>(GuidePublishStepsJob.serializer()) {

    override suspend fun getLockId(): String {
        return getJobDefinition().id.toString()
    }

    override suspend fun execute() {
        val job = getJobDefinition()
        val metadata = metadataService.getById(job.id, job.version) ?: return
        if (metadata.contentType != "bosca/v-guide") return

        val steps = guideService.getGuideSteps(metadata.id, metadata.version)
        if (steps.isEmpty()) return

        val sa = securityService.impersonate("sa")
        for (step in steps) {
            val stepId = step.stepMetadataId ?: continue
            val stepVersion = step.stepMetadataVersion ?: 1
            var stepMetadata = metadataService.getById(stepId, stepVersion) ?: continue
            if (stepMetadata.isPublished) continue
            if (stepMetadata.ready == null) {
                stepMetadata = metadataService.setReady(stepMetadata, sa.principal().asPrincipal())
            }
            if (stepMetadata.workflowStatePendingId != null) {
                stepMetadata = MetadataTransitionExecutor.execute(metadataService, transitioner, stepMetadata, sa) as Metadata
            }
            if (stepMetadata.workflowStateId == "pending") {
                stepMetadata = metadataService.setPendingState(
                    stepMetadata,
                    "draft",
                    status = "Moving from pending to draft",
                    principal = sa.principal().asPrincipal()
                )
                stepMetadata = MetadataTransitionExecutor.execute(metadataService, transitioner, stepMetadata, sa) as Metadata
            }
            transitioner.beginTransition(
                sa,
                BeginTransitionInput(
                    metadataId = stepMetadata.id,
                    version = stepMetadata.version,
                    stateId = "published",
                    status = "Publishing step because guide was published"
                )
            )
        }
    }
}
