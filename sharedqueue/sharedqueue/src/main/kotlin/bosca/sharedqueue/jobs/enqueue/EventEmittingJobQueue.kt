package bosca.sharedqueue.jobs.enqueue

import bosca.serialization.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobCallback
import bosca.sharedqueue.jobs.JobQueue
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class EventEmittingJobQueue(
    private val delegate: JobQueue,
    private val queueName: String,
    private val channel: JobEnqueueEventChannel,
    private val callbacks: List<JobCallback> = emptyList()
) : JobQueue by delegate {

    override suspend fun enqueue(job: Job): UUID {
        if (!job.disableEnqueueCallbacks) addCallbacks(job)
        val id = delegate.enqueue(job)
        if (!job.disableEmitEvent) {
            emitEvent(id, job, null)
        }
        return id
    }

    override suspend fun enqueueLater(job: Job, timeout: Duration): UUID {
        if (!job.disableEnqueueCallbacks) addCallbacks(job)
        val id = delegate.enqueueLater(job, timeout)
        if (!job.disableEmitEvent) {
            val delayedUntil = OffsetDateTime.now().plus(timeout.toJavaDuration()).takeIf {
                it.isAfter(OffsetDateTime.now().plusMinutes(3))
            }
            emitEvent(id, job, delayedUntil)
        }
        return id
    }

    private fun addCallbacks(job: Job) {
        for (callback in callbacks) {
            job.addCallback(callback, true)
        }
    }

    private suspend fun emitEvent(jobId: UUID, job: Job, delayedUntil: OffsetDateTime?) {
        try {
            channel.emit(
                JobEnqueueEvent(
                    jobId = jobId,
                    executor = job.executor.qualifiedName ?: job.executor.simpleName ?: "unknown",
                    executorName = job.executorName,
                    displayName = job.displayName,
                    queue = queueName,
                    enqueuedAt = OffsetDateTime.now(),
                    delayed = delayedUntil != null,
                    delayedUntil = delayedUntil,
                    definition = job.definition,
                    context = job.getContext()
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to emit job enqueue event for job $jobId", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(EventEmittingJobQueue::class.java)
    }
}
