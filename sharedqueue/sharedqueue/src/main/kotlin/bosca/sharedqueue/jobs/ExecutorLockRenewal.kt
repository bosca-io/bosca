package bosca.sharedqueue.jobs

import bosca.lock.DistributedLock
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

private val log = LoggerFactory.getLogger("bosca.sharedqueue.jobs.ExecutorLockRenewal")

/**
 * Background renewal loop that keeps an executor-level [DistributedLock]
 * alive across an arbitrarily long `execute()` call.
 *
 * Extracted from `JobRunner` so its behaviour can be exercised under
 * `runTest` virtual time without spinning up the full runner, dispatcher,
 * queue, and DI stack. The loop sleeps for [intervalMillis] between renew
 * attempts and re-extends the lock to [ttlMillis] on each successful
 * renewal, matching the pattern used by the existing job-level checkin
 * loop in `JobRunner.checkin`.
 *
 * Termination conditions, in priority order:
 *  1. The enclosing coroutine is cancelled — the loop exits cleanly.
 *  2. A [DistributedLock.renew] call returns `false`, meaning the backing
 *     store no longer considers this lock owned by us (TTL expired, or
 *     another worker somehow claimed it). The loop exits and logs a
 *     warning; the caller's running `execute()` will continue until it
 *     finishes on its own, but it is no longer protected by the lock.
 *     That is an irrecoverable state from the renewal loop's perspective:
 *     re-acquiring here would race with whoever took the lock.
 *  3. A transient exception during renewal (e.g. Redis blip) is caught
 *     and logged; the loop continues so a momentary backend hiccup does
 *     not orphan a healthy executor.
 *
 * @param lock the lock to keep alive — must already be held by this instance
 * @param ttlMillis the TTL to request on each renewal
 * @param intervalMillis how long to sleep between renewals; must be
 *                      meaningfully less than [ttlMillis] so a single
 *                      missed renewal cannot expire the lock
 * @param jobId the id of the job whose executor owns the lock, for logging
 */
internal suspend fun renewExecutorLockLoop(
    lock: DistributedLock,
    ttlMillis: Long,
    intervalMillis: Long,
    jobId: UUID,
) {
    while (currentCoroutineContext().isActive) {
        try {
            delay(intervalMillis.milliseconds)
            if (!lock.renew(ttlMillis)) {
                log.warn(
                    "Failed to renew executor lock for job {} — another worker may have taken it",
                    jobId,
                )
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Error renewing executor lock for job $jobId", e)
        }
    }
}
