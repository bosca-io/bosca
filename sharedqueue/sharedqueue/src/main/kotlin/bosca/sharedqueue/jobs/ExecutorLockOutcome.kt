package bosca.sharedqueue.jobs

import bosca.lock.DistributedLock

/**
 * The three possible outcomes of attempting to acquire an executor-level
 * distributed lock at the start of a job run, as decided by
 * [acquireExecutorLockOrDecide].
 */
internal enum class ExecutorLockOutcome {
    /**
     * Either no lock was requested (the executor did not provide a
     * [JobExecutor.getLockId]) or the acquisition succeeded. The caller
     * should proceed to run `executor.execute()`.
     */
    PROCEED,

    /**
     * The lock was already held by a sibling executor and the current
     * executor has opted into [JobExecutor.skipExecutionIfLocked]. The
     * caller should skip `executor.execute()` entirely and mark the job
     * complete without retrying — the sibling's in-flight run already
     * subsumes the work this job would have done.
     */
    SKIP,

    /**
     * The lock was already held by a sibling executor and the current
     * executor did not opt into [JobExecutor.skipExecutionIfLocked]. The
     * caller should requeue the job with a short delay so it can retry
     * once the sibling releases the lock.
     */
    DELAY,
}

/**
 * Decides what the runner should do when it is about to call
 * `executor.execute()` but must first claim the executor-level lock.
 *
 * The logic is intentionally linear and side-effect-free (aside from the
 * lock acquisition itself) so it can be unit-tested without spinning up a
 * full [JobRunner]:
 *
 * 1. No lock requested → [ExecutorLockOutcome.PROCEED] immediately.
 *    Executors that do not override [JobExecutor.getLockId] get the
 *    legacy "run unconditionally" behaviour.
 * 2. `tryAcquire` succeeds → [ExecutorLockOutcome.PROCEED]. The caller
 *    now holds the lock and is responsible for releasing it.
 * 3. `tryAcquire` fails and [skipIfLocked] is `true` →
 *    [ExecutorLockOutcome.SKIP]. A sibling is currently running; the
 *    new job is redundant and should be completed silently.
 * 4. `tryAcquire` fails and [skipIfLocked] is `false` →
 *    [ExecutorLockOutcome.DELAY]. A sibling is currently running; the
 *    new job must eventually run, so requeue it with a short delay.
 *
 * Exceptions thrown from `tryAcquire` (e.g. the backend being
 * unreachable) are **not** caught here — they propagate to the runner's
 * outer exception handler, which treats them as a normal job failure and
 * retries per the queue's retry policy. Swallowing backend errors here
 * would cause jobs to silently drop on the floor during a Redis outage.
 */
internal suspend fun acquireExecutorLockOrDecide(
    lock: DistributedLock?,
    ttlMillis: Long,
    skipIfLocked: Boolean,
): ExecutorLockOutcome {
    if (lock == null) return ExecutorLockOutcome.PROCEED
    if (lock.tryAcquire(ttlMillis)) return ExecutorLockOutcome.PROCEED
    return if (skipIfLocked) ExecutorLockOutcome.SKIP else ExecutorLockOutcome.DELAY
}
