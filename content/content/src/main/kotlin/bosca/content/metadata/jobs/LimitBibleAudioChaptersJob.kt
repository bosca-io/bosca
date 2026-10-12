package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.BibleService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

/** Trims retained chapter recordings for one upstream edition. */
@Serializable
data class LimitBibleAudioChaptersJob(val provider: String, val providerBibleId: String) : IJobDefinition

@JobDefinition(definition = LimitBibleAudioChaptersJob::class, queue = JobQueueNames.contentJobQueue,
    name = "limit-bible-audio-chapters", displayName = "Limit Bible Audio Chapters")
class LimitBibleAudioChaptersExecutor(private val bibles: BibleService) :
    AbstractJobExecutor<LimitBibleAudioChaptersJob>(LimitBibleAudioChaptersJob.serializer()) {
    override suspend fun execute() {
        val job = getJobDefinition()
        bibles.limitAudioChapterContent(job.provider, job.providerBibleId)
    }
}
