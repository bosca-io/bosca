package bosca.pipelines.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.pipelines.trigger.PipelineRetentionSweepExecutor
import bosca.pipelines.trigger.PipelineRetentionSweepJob
import bosca.pipelines.trigger.PipelineSuspendedSweepExecutor
import bosca.pipelines.trigger.PipelineSuspendedSweepJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Registers the pipelines periodic jobs as cron-scheduled jobs (idempotently — only creates one whose
 * [ScheduledJobInput.jobName] is not already registered). The platform `SchedulerRunner` evaluates
 * these on its cadence and enqueues them onto the pipelines queue.
 */
class PipelineScheduledJobsInstaller(
    private val schedulerService: SchedulerService,
    private val json: Json,
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existing = schedulerService.getJobs().map { it.jobName }.toSet()

        if (PipelineSuspendedSweepExecutor.NAME !in existing) {
            log.info("creating scheduled job '{}'", PipelineSuspendedSweepExecutor.NAME)
            schedulerService.createJob(
                ScheduledJobInput(
                    name = "Pipeline Suspended-Run Sweep",
                    description = "Fails pipeline runs stuck suspended past their configured max lifetime",
                    jobName = PipelineSuspendedSweepExecutor.NAME,
                    jobParameters = json.encodeToJsonElement(PipelineSuspendedSweepJob.serializer(), PipelineSuspendedSweepJob()),
                    cronExpression = "*/15 * * * *",
                    enabled = true,
                    allowConcurrent = false,
                    catchUp = false,
                    maxCatchUp = 1,
                ),
                createdBy = UUID.NIL,
            )
        }

        // Run-retention sweep: reap terminal run-state + old history daily.
        if (PipelineRetentionSweepExecutor.NAME !in existing) {
            log.info("creating scheduled job '{}'", PipelineRetentionSweepExecutor.NAME)
            schedulerService.createJob(
                ScheduledJobInput(
                    name = "Pipeline Run Retention Sweep",
                    description = "Soft-deletes terminal run-state and deletes old run-history past their retention windows",
                    jobName = PipelineRetentionSweepExecutor.NAME,
                    jobParameters = json.encodeToJsonElement(PipelineRetentionSweepJob.serializer(), PipelineRetentionSweepJob()),
                    // Weekly — Sundays at 03:00 (minute hour day-of-month month day-of-week).
                    cronExpression = "0 3 * * 0",
                    enabled = true,
                    allowConcurrent = false,
                    catchUp = false,
                    maxCatchUp = 1,
                ),
                createdBy = UUID.NIL,
            )
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineScheduledJobsInstaller::class.java)
    }
}
