package bosca.analytics.jobs

import bosca.analytics.configuration.JobQueueNames
import bosca.analytics.transform.fums.FumsReporter
import bosca.bible.providers.service.BibleProviderService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.job
import org.slf4j.LoggerFactory
import java.io.IOException

/** Resolves the private response token and delivers usage on the FUMS worker. */
@JobDefinition(FumsDeliveryJob::class, queue = JobQueueNames.fumsJobQueue, name = "fums-delivery", displayName = "Report Bible Usage")
class FumsDeliveryExecutor(
    private val bibles: BibleProviderService,
    private val reporter: FumsReporter,
) : AbstractJobExecutor<FumsDeliveryJob>(FumsDeliveryJob.serializer()) {

    override suspend fun execute() {
        val usage = getJobDefinition()
        val jobId = job().getId()
        log.info("FUMS delivery processing: jobId={} bibleId={} chapter={} kind={}",
            jobId, usage.bibleId, usage.usfm, if (usage.audioId == null) "text" else "audio")
        val token = bibles.getFumsToken(usage.bibleId, usage.version, usage.variant, usage.usfm, usage.audioId)
        if (token == null) {
            log.info("FUMS delivery skipped: jobId={} reason=missing_or_expired_token", jobId)
            return
        }
        val status = try {
            reporter.report(token, usage.deviceId, usage.sessionId, usage.userId)
        } catch (failure: IOException) {
            // FumsReporter redacts request URLs and response bodies from transport failures.
            log.warn("FUMS delivery failed: jobId={} reason={}", jobId, failure.message)
            throw failure
        }
        log.info("FUMS delivery accepted by API.Bible: jobId={} httpStatus={}", jobId, status)
    }

    private companion object {
        val log = LoggerFactory.getLogger(FumsDeliveryExecutor::class.java)
    }
}
