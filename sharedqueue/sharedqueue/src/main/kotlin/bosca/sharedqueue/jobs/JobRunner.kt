package bosca.sharedqueue.jobs

import bosca.cache.withRequestCache
import bosca.db.withConnectionManager
import bosca.di.ObjectProvider
import bosca.di.asProvider
import bosca.events.withEventManager
import bosca.lock.DistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.sharedqueue.jobs.JobRunner.Companion.EXECUTOR_LOCK_RENEW_INTERVAL_MILLIS
import bosca.sharedqueue.jobs.JobRunner.Companion.EXECUTOR_LOCK_TTL_MILLIS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job as CoroutineJob

class JobRunner(
    private val queue: JobQueue,
    private val max: Int,
    private val distributedLockFactory: DistributedLockFactory,
    private val errorCapture: ObjectProvider<ErrorCapture> = ErrorCapture.Noop.asProvider()
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private suspend fun captureError(throwable: Throwable, context: Map<String, Any?>) {
        try {
            errorCapture.get().capture(throwable, null, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("error capturing error", e)
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private val active = AtomicLong(0)

    fun run() {
        repeat(max) {
            scope.launch { process() }
        }
    }

    private fun checkin(job: Job) = scope.launch {
        while (isActive) {
            try {
                if (!queue.checkin(job, 1_800_000)) {
                    error("failed to checkin: ${job.id}")
                }
            } catch (_: CancellationException) {
                log.info("cancelled checkin: ${job.id}")
                return@launch
            } catch (e: Exception) {
                log.warn("failed to checkin: ${job.id}", e)
            }
            try {
                delay(60_000.milliseconds)
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                log.warn("failed to checkin delay: ${job.id}", e)
            }
        }
    }

    private suspend fun process() {
        var errorDelay = 1_000L
        var idleDelay = 1L
        while (scope.isActive) {
            // Dequeue acquires the job lock and starts the delivery's acknowledgement timer.
            // Each worker fetches only when ready to start check-in and execution.
            val job = try {
                queue.dequeue().also { errorDelay = 1_000L }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("error fetching next job: $queue", e)
                captureError(e, mapOf("queue" to queue.toString()))
                delay(errorDelay.milliseconds)
                errorDelay = minOf(errorDelay * 2, 10_000L)
                continue
            }
            if (job == null) {
                // NATS already waits inside dequeue; this backoff also keeps non-blocking queues idle.
                delay(idleDelay.milliseconds)
                idleDelay = minOf(idleDelay * 2, 1_000L)
                continue
            }
            idleDelay = 1L
            try {
                val checkin = checkin(job)
                try {
                    job.execute(checkin)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            checkin.cancelAndJoin()
                        } finally {
                            job.lock?.release()
                        }
                    }
                }
            } catch (e: CancellationException) {
                log.info("cancelled job: ${job.id}")
                throw e
            } catch (e: Throwable) {
                log.error("error processing job: ${job.id}", e)
                captureError(e, mapOf("job.id" to job.id, "job.executor" to job.executorName))
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun Job.execute(checkinJob: CoroutineJob) {
        withContext(queue.asCoroutineContext(this)) {
            try {
                active.incrementAndFetch()
                val lock = lock ?: error("Job $id is missing lock")
                if (!lock.isHeld && !lock.renew(60_000)) {
                    error("Failed to acquire lock for child job $id")
                }
                setStatus(JobStatus.RUNNING)
                queue.setJob(this@execute)
                val executor = newExecutor()
                withRequestCache {
                    withConnectionManager {
                        notifyStatusChanged(this@execute, JobStatus.RUNNING, null)
                        // The queue check-in coroutine is the job's liveness mechanism. Healthy executors
                        // may legitimately run for hours, so execution has no separate wall-clock cutoff.
                        withEventManager {
                            // Acquire the executor-level distributed lock if the
                            // executor defines one. This is what serializes sibling
                            // jobs that share a logical entity (e.g. two
                            // UploadToMuxJobs enqueued for the same metadata id):
                            // both will dequeue, but only one will hold the lock
                            // and run at a time. What happens to the loser depends
                            // on [JobExecutor.skipExecutionIfLocked]:
                            //
                            //  - false (default): throw DelayException and requeue
                            //    so it retries once the sibling releases the lock.
                            //  - true: silently mark complete — the sibling's
                            //    in-flight run already subsumes this job's work.
                            //
                            // The lock uses a short TTL that is kept alive by a
                            // background renewal coroutine. This keeps crash
                            // recovery fast (a dead worker's lock becomes
                            // reacquirable within roughly EXECUTOR_LOCK_TTL_MILLIS)
                            // while still surviving execute() calls of arbitrary
                            // length.
                            val executorLock = executor.getLockId()?.let {
                                distributedLockFactory.create("runner-$it")
                            }
                            val outcome = acquireExecutorLockOrDecide(
                                lock = executorLock,
                                ttlMillis = EXECUTOR_LOCK_TTL_MILLIS,
                                skipIfLocked = executor.skipExecutionIfLocked,
                            )
                            when (outcome) {
                                ExecutorLockOutcome.DELAY -> {
                                    log.debug(
                                        "executor lock {} is held, delaying job {}",
                                        executor.getLockId(), id,
                                    )
                                    throw DelayException(EXECUTOR_LOCK_RETRY_DELAY)
                                }
                                ExecutorLockOutcome.SKIP -> {
                                    log.info(
                                        "executor lock {} is held and job {} opts to skip; marking complete without running",
                                        executor.getLockId(), id,
                                    )
                                    // Fall through to the checkin/markComplete
                                    // block below. No lock to release (we never
                                    // acquired it) and no renewal to cancel.
                                }
                                ExecutorLockOutcome.PROCEED -> {
                                    val lockRenewal: CoroutineJob? = executorLock?.let { heldLock ->
                                        scope.launch {
                                            renewExecutorLockLoop(
                                                lock = heldLock,
                                                ttlMillis = EXECUTOR_LOCK_TTL_MILLIS,
                                                intervalMillis = EXECUTOR_LOCK_RENEW_INTERVAL_MILLIS,
                                                jobId = id,
                                            )
                                        }
                                    }
                                    try {
                                        executor.execute()
                                    } finally {
                                        lockRenewal?.cancelAndJoin()
                                        executorLock?.let { lock ->
                                            runCatching {
                                                lock.release()
                                            }
                                        }
                                    }
                                }
                            }
                            try {
                                checkinJob.cancelAndJoin()
                            } finally {
                                queue.markComplete(this@execute)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                checkinJob.cancelAndJoin()
                log.warn("job $id - $executor - $executorName - cancelled")
                queue.markFailed(this@execute, e, true)
            } catch (e: DelayException) {
                checkinJob.cancelAndJoin()
                log.warn("job $id - $executor - $executorName - delayed for ${e.time.inWholeSeconds} seconds")
                queue.enqueueLater(this@execute, e.time)
            } catch (e: FailException) {
                checkinJob.cancelAndJoin()
                log.warn("job $id - $executor - $executorName - failed with a fail exception, not retrying", e)
                queue.markFailed(this@execute, e, false)
                captureError(e, mapOf("job.id" to id, "job.executor" to executorName, "job.retryable" to false))
            } catch (e: Exception) {
                checkinJob.cancelAndJoin()
                log.error("job $id - $executor - $executorName - failed with exception", e)
                queue.markFailed(this@execute, e, true)
                captureError(e, mapOf("job.id" to id, "job.executor" to executorName, "job.retryable" to true))
            } finally {
                active.decrementAndFetch()
            }
        }
    }

    fun shutdown() {
        log.info("shutting down")
        scope.cancel()
    }

    private suspend fun notifyStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        for (callback in job.callbacks) {
            try {
                callback.newListener().onStatusChanged(job, status, errorMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("status-change listener ${callback::class.qualifiedName} failed for job ${job.id}", e)
            }
        }
    }

    companion object {
        private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
        private val log = LoggerFactory.getLogger(JobRunner::class.java)

        /**
         * TTL applied when acquiring (and subsequently renewing) an
         * executor-level lock via [JobExecutor.getLockId]. Deliberately short
         * so that a crashed worker's lock becomes reacquirable quickly; a
         * background renewal loop re-extends this TTL every
         * [EXECUTOR_LOCK_RENEW_INTERVAL_MILLIS] so normally-executing jobs
         * never lose their lock regardless of how long they run.
         */
        private const val EXECUTOR_LOCK_TTL_MILLIS: Long = 2L * 60 * 1000

        /**
         * Interval at which the background renewal coroutine re-extends the
         * executor lock. Must be comfortably less than
         * [EXECUTOR_LOCK_TTL_MILLIS] so a single missed renewal (backend
         * blip, GC pause) does not expire the lock.
         */
        private const val EXECUTOR_LOCK_RENEW_INTERVAL_MILLIS: Long = 45L * 1000

        /**
         * How long to delay a job whose executor lock is currently held by
         * a sibling. Short enough to feel responsive, long enough to avoid
         * spinning the worker when the sibling's work is non-trivial.
         */
        private val EXECUTOR_LOCK_RETRY_DELAY = 5.seconds
    }
}

val JobsDispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
