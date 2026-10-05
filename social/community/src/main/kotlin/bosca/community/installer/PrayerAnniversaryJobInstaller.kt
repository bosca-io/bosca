package bosca.community.installer

import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.UUID
import org.slf4j.LoggerFactory

class PrayerAnniversaryJobInstaller(
    private val schedulerService: SchedulerService
) : PackageInstaller {

    override val version: String = "1.0.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existingJobs = schedulerService.getJobs()
        val existingJobNames = existingJobs.map { it.jobName }.toSet()

        if (JOB_NAME in existingJobNames) {
            log.info("Scheduled job '{}' already exists, skipping", JOB_NAME)
            return
        }

        log.info("Creating scheduled job '{}'", JOB_NAME)
        schedulerService.createJob(
            ScheduledJobInput(
                name = "Scan Prayer Anniversaries",
                description = "Daily scan for answered prayer milestones to post Praise & Celebration cards",
                jobName = JOB_NAME,
                cronExpression = "0 3 * * *",
                enabled = true,
                allowConcurrent = false,
                catchUp = false,
                maxCatchUp = 1
            ),
            createdBy = UUID.NIL
        )
    }

    companion object {
        private const val JOB_NAME = "scan-prayer-anniversaries"
        private val log = LoggerFactory.getLogger(PrayerAnniversaryJobInstaller::class.java)
    }
}
