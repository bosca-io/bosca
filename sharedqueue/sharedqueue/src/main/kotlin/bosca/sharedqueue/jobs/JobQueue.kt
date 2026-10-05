package bosca.sharedqueue.jobs

import bosca.serialization.UUID
import bosca.sharedqueue.Queue
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration

interface JobQueue : Queue<Job> {

    /**
     * The queue's physical name (e.g. `workops`, `pipelines`) — the same string the queue was
     * [JobQueueFactory.create]d with. Persisted onto every enqueued job (and, via
     * [Job.addChild], onto children as their parent's queue) so a completion on one queue can
     * locate its parent job on another (see [bosca.sharedqueue.jobs.listeners.NotifyParentListener]).
     */
    val name: String

    /**
     * Attempts to dequeue a job, waiting no longer than [waitTimeout] for work.
     *
     * Backends whose ordinary [dequeue] is already non-blocking may use this default implementation.
     */
    suspend fun dequeue(waitTimeout: Duration): Job? = dequeue()

    suspend fun <T> getJob(id: UUID, block: suspend (Job?) -> T): T

    /**
     * Enqueues a job with a caller-assigned persistent ID only when this queue does not already
     * contain durable state for that ID.
     *
     * This is the recovery operation for database-outbox producers. If durable state already owns
     * the ID, the implementation must leave that state and its delivery bookkeeping unchanged.
     * Once state is accepted, the queue backend owns recovery of a delivery interrupted between
     * persisting the state and publishing its notification.
     *
     * @return the caller-assigned job ID
     */
    suspend fun enqueueIfAbsent(job: Job): UUID

    suspend fun setJob(job: Job)

    suspend fun checkin(job: Job, lockRenew: Long): Boolean

    suspend fun setDefinition(job: Job, definition: JsonElement)

    suspend fun markFailed(job: Job, exception: Exception, retry: Boolean)

    suspend fun markComplete(job: Job)

    suspend fun markCancelled(id: UUID)

    suspend fun checkForExpiredJobs(time: Long = Clock.System.now().toEpochMilliseconds())

    suspend fun expireAllJobs()

    /**
     * Releases the lock held by this consumer for [job].
     *
     * Unlike [clearJobLock], this only releases the lock when this job instance still owns it.
     */
    suspend fun releaseJobLock(job: Job) {
        job.lock?.takeIf { it.isHeld }?.release()
        job.lock = null
    }

    /**
     * Force-releases the distributed lock for the job with the given [id],
     * allowing it to be re-acquired by another worker.
     */
    suspend fun clearJobLock(id: UUID)

    /**
     * Force-releases all distributed locks held by jobs in this queue.
     * This is an administrative recovery operation for clearing stale locks
     * left by crashed workers.
     */
    suspend fun clearAllJobLocks()
}
