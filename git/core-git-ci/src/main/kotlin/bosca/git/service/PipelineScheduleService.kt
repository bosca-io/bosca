package bosca.git.service

import bosca.git.model.Pipeline
import bosca.git.model.PipelineTrigger
import bosca.scheduler.model.ScheduledJob
import bosca.serialization.UUID
import bosca.service.Service

/** Mirrors Git YAML cron triggers into the platform scheduler. */
interface PipelineScheduleService : Service {

    /** Reconciles [pipeline]'s default-branch cron triggers with principal-required scheduler jobs. */
    suspend fun sync(pipeline: Pipeline, triggers: List<PipelineTrigger>): List<ScheduledJob>

    /** Finds a Git pipeline scheduler job, rejecting scheduler jobs owned by other domains. */
    suspend fun findById(id: UUID): ScheduledJob?

    suspend fun findByPipeline(pipelineId: UUID): List<ScheduledJob>

    /** Removes one stale platform scheduler entry. */
    suspend fun delete(id: UUID)

    /** Removes all schedules before deleting their owning pipeline. */
    suspend fun deleteByPipeline(pipelineId: UUID)
}
