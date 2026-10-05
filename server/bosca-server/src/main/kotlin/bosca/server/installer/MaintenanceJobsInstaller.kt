package bosca.server.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.scheduler.jobs.PurgeJobHistoryJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.security.jobs.DeleteExpiredSecurityTokensJob
import bosca.scripting.jobs.PurgeEphemeralScriptsJob
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory

/**
 * Installs scheduled platform maintenance jobs. Each job is only created if no scheduled job
 * with the same job name exists, ensuring idempotent installation.
 */
class MaintenanceJobsInstaller(
    private val schedulerService: SchedulerService,
    private val json: Json
) : PackageInstaller {

    override val version: String = VERSION

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existingJobs = schedulerService.getJobs()
        val existingJobNames = existingJobs.map { it.jobName }.toSet()

        for (job in jobs) {
            if (job.jobName in existingJobNames) {
                log.info("Scheduled job '{}' already exists, skipping", job.jobName)
                continue
            }
            log.info("Creating scheduled job '{}'", job.jobName)
            schedulerService.createJob(job, createdBy = UUID.NIL)
        }
    }

    private val jobs = listOf(
        ScheduledJobInput(
            name = "Finalize Deletion",
            description = "Permanently removes soft-deleted metadata and collections",
            jobName = "finalize-deletion",
            cronExpression = "0 * * * *",
            enabled = true,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1
        ),
        ScheduledJobInput(
            name = "Purge Ephemeral Scripts",
            description = "Permanently removes soft-deleted ephemeral scripts older than 7 days",
            jobName = "purge-ephemeral-scripts",
            jobParameters = json.encodeToJsonElement(PurgeEphemeralScriptsJob(retentionDays = 7)),
            cronExpression = "0 3 * * *",
            enabled = true,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1
        ),
        ScheduledJobInput(
            name = "Purge Job History",
            description = "Removes completed scheduler job history older than 30 days",
            jobName = "purge-job-history",
            jobParameters = json.encodeToJsonElement(PurgeJobHistoryJob(retentionDays = 30)),
            cronExpression = "0 0 * * *",
            enabled = true,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1
        ),
        ScheduledJobInput(
            name = "Delete Expired Security Tokens",
            description = "Removes expired refresh tokens, exchange tokens, and login revocations",
            jobName = "delete-expired-security-tokens",
            jobParameters = json.encodeToJsonElement(DeleteExpiredSecurityTokensJob()),
            cronExpression = "*/30 * * * *",
            enabled = true,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1
        ),
        ScheduledJobInput(
            name = "Recover Message Outbox",
            description = "Recovers committed communications outbox rows not yet owned by the delivery queue",
            jobName = "message-outbox-maintenance",
            cronExpression = "* * * * *",
            enabled = true,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1
        ),
        ScheduledJobInput(
            name = "Maintain WorkOps Notifications",
            description = "Recovers notification outbox rows and dispatches due digests and task notifications",
            jobName = "notification-maintenance",
            cronExpression = "* * * * *",
            enabled = true,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1
        ),
    )

    companion object {
        const val VERSION = "1.3.0"
        private val log = LoggerFactory.getLogger(MaintenanceJobsInstaller::class.java)
    }
}
