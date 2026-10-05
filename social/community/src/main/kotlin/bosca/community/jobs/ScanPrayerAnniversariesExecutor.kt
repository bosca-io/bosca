package bosca.community.jobs

import bosca.community.configuration.JobQueueNames
import bosca.community.service.PrayerService
import bosca.di.provide
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

@JobDefinition(
    ScanPrayerAnniversariesJob::class,
    JobQueueNames.communityJobQueue,
    "scan-prayer-anniversaries",
    displayName = "Scan Prayer Anniversaries"
)
class ScanPrayerAnniversariesExecutor :
    AbstractJobExecutor<ScanPrayerAnniversariesJob>(ScanPrayerAnniversariesJob.serializer()) {

    override suspend fun execute() {
        log.info("Starting prayer anniversary scan")
        val prayerService: PrayerService = provide()
        prayerService.scanAndPostAnniversaries()
        log.info("Prayer anniversary scan complete")
    }

    companion object {
        private val log = LoggerFactory.getLogger(ScanPrayerAnniversariesExecutor::class.java)
    }
}
