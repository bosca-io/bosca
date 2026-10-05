package bosca.sharedqueue.jobs

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

fun JobQueue.asCoroutineContext(job: Job): CoroutineContext = JobQueueContext(this, job)

private class JobQueueContext(val queue: JobQueue, val job: Job) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<JobQueueContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

suspend fun jobQueue(): JobQueue = currentCoroutineContext().jobQueue()

suspend fun job(): Job = currentCoroutineContext().job()

/** The job driving the current coroutine, or `null` when not running inside a job (e.g. an inline call). */
suspend fun jobOrNull(): Job? = currentCoroutineContext()[JobQueueContext.Key]?.job

/** The queue bound to the current coroutine, or `null` when not running inside a job. */
suspend fun jobQueueOrNull(): JobQueue? = currentCoroutineContext()[JobQueueContext.Key]?.queue

fun CoroutineContext.jobQueue(): JobQueue {
    return this[JobQueueContext.Key]?.queue ?: throw IllegalStateException("JobQueue not found in coroutine context")
}

fun CoroutineContext.job(): Job {
    return this[JobQueueContext.Key]?.job ?: throw IllegalStateException("Job not found in coroutine context")
}