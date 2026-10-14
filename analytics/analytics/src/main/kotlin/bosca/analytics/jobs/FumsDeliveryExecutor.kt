package bosca.analytics.jobs

import bosca.analytics.configuration.JobQueueNames
import bosca.analytics.transform.fums.FumsReporter
import bosca.bible.providers.service.BibleProviderService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor

/** Resolves the private response token and delivers usage on the FUMS worker. */
@JobDefinition(FumsDeliveryJob::class, queue = JobQueueNames.fumsJobQueue, name = "fums-delivery", displayName = "Report Bible Usage")
class FumsDeliveryExecutor(
    private val bibles: BibleProviderService,
    private val reporter: FumsReporter,
) : AbstractJobExecutor<FumsDeliveryJob>(FumsDeliveryJob.serializer()) {

    override suspend fun execute() {
        val usage = getJobDefinition()
        val token = bibles.getFumsToken(usage.bibleId, usage.version, usage.variant, usage.usfm, usage.audioId) ?: return
        reporter.report(token, usage.deviceId, usage.sessionId, usage.userId)
    }
}
