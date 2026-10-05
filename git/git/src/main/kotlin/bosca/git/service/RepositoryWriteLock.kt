package bosca.git.service

import bosca.git.dfs.BoscaDfsObjDatabase
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.slf4j.LoggerFactory

/**
 * Per-repository write lock that serializes every operation which mutates a
 * repository's DFS pack store — pushes (receive-pack + thin-pack compaction),
 * API/UI file commits, PR merges, mirror fetch, import/restore, garbage
 * collection, and repair.
 *
 * ## Why writers must be serialized
 *
 * GC computes object reachability from a snapshot of the repository's refs. A
 * writer that inserts objects and advances a ref concurrently can make GC
 * misjudge a still-reachable object as garbage, dropping it from the
 * consolidated pack. Serializing writers on the same repository closes that
 * race. It also collapses the concurrent-push races (two pushes producing
 * conflicting/orphaned packs) into a clean sequence.
 *
 * ## Why reads do NOT take this lock
 *
 * Clone/fetch (upload-pack) intentionally run lock-free. They stay correct across
 * a concurrent pack swap because GC/compaction only SOFT-delete the packs they
 * replace; the physical object-storage files survive a grace window before the
 * reaper removes them (see [RepositoryLifecycleService.reapDeletedPacks]). An
 * in-flight reader therefore keeps reading the exact packs it listed, and never
 * blocks a background GC.
 */
object RepositoryWriteLock {

    /**
     * Lock time-to-live. Deliberately short so a crashed holder's lock becomes
     * reacquirable quickly; kept alive across long operations by renewing every
     * [RENEW_INTERVAL_MILLIS].
     */
    const val TTL_MILLIS = 30_000L
    const val RENEW_INTERVAL_MILLIS = 10_000L
    const val RETRY_DELAY_MILLIS = 100L

    /**
     * How long a user-facing push waits for the lock before giving up with a
     * retryable error. Generous because the only realistic contention is a
     * sibling push or the weekly GC window.
     */
    const val PUSH_WAIT_MILLIS = 120_000L

    /**
     * How long a GraphQL-driven write (Studio file commit/delete, PR merge)
     * waits for the lock before failing with [RepositoryWriteBusyException].
     * Shorter than [PUSH_WAIT_MILLIS]: these are interactive requests, so a
     * fast, clearly retryable failure beats holding the caller's request open.
     */
    const val API_WRITE_WAIT_MILLIS = 30_000L

    /**
     * How long background GC/repair waits for the lock before skipping this
     * cycle. Short: background maintenance yields to live pushes; the caller is
     * told the cycle was skipped (see [RepositoryLifecycleService.runGc]) so the
     * scheduled job can requeue itself instead of losing the cycle.
     */
    const val MAINTENANCE_WAIT_MILLIS = 60_000L

    fun key(repositoryId: UUID): String = "git-repo-write-$repositoryId"

    val log: org.slf4j.Logger = LoggerFactory.getLogger(RepositoryWriteLock::class.java)
}

/**
 * Thrown when the write lock's TTL renewal fails mid-operation (the backing store
 * reports the lock is no longer held). Callers must abort rather than continue
 * without exclusivity. Not a [kotlinx.coroutines.CancellationException], so it
 * propagates normally.
 */
class RepositoryWriteLockLostException(key: String) :
    IllegalStateException("Lost repository write lock '$key' during operation")

/**
 * Thrown when a write operation could not acquire the repository write lock
 * within its wait budget. The repository state is untouched; the caller can
 * simply retry once the competing writer finishes.
 */
class RepositoryWriteBusyException(repositoryId: UUID) :
    IllegalStateException("Repository $repositoryId is busy with another write operation, please retry shortly")

/**
 * Live view of a held write lock, passed to the [withRepositoryWriteLock] block.
 */
class RepositoryWriteLockHandle internal constructor(
    private val key: String,
    private val lock: DistributedLock,
) {
    @Volatile
    private var lost = false

    internal fun markLost() {
        lost = true
    }

    /**
     * Fencing check for blocking critical sections that coroutine-level
     * cancellation cannot interrupt (JGit's GC/compaction commits its pack swap
     * inside one uninterruptible blocking call). Call this immediately before
     * the irreversible commit point — see
     * [bosca.git.dfs.BoscaDfsObjDatabase.commitPacksFence]. Fails fast when a
     * background renewal has already failed, then confirms ownership with a
     * synchronous renewal round trip to the lock backend.
     *
     * Uses [runBlocking], so it must only be invoked from JGit's blocking
     * callbacks running on [bosca.git.dfs.GitBlockingDispatcher], never from
     * suspending code or a Netty event loop.
     *
     * @throws RepositoryWriteLockLostException if the lock is no longer held.
     */
    fun ensureHeld() {
        if (lost) throw RepositoryWriteLockLostException(key)
        if (!runBlocking { lock.renew(RepositoryWriteLock.TTL_MILLIS) }) {
            lost = true
            throw RepositoryWriteLockLostException(key)
        }
    }
}

/**
 * Makes every pack commit on [repository] first confirm this lock is still held (see
 * [RepositoryWriteLockHandle.ensureHeld]), so a write whose lock was lost mid-operation commits
 * nothing. Set it right after opening the repository inside the locked block.
 */
fun RepositoryWriteLockHandle.fence(repository: DfsRepository) {
    (repository.objectDatabase as? BoscaDfsObjDatabase)?.commitPacksFence = ::ensureHeld
}

/**
 * Renews [lock], reporting any failure as `false`: a backend error or timeout is a failed renewal,
 * which aborts the operation, not a reason to stop renewing and let the lock expire under it. The
 * renewer runs under NonCancellable, so a CancellationException here is the backend's own timeout
 * (an exhausted connection pool, for example), never the renewer being cancelled.
 */
private suspend fun renewOrFalse(lock: DistributedLock, key: String): Boolean = try {
    lock.renew(RepositoryWriteLock.TTL_MILLIS)
} catch (e: Exception) {
    RepositoryWriteLock.log.warn("Renewing write lock {} failed", key, e)
    false
}

/**
 * Acquires the per-repository write lock, runs [block] while a background loop
 * renews the TTL, then releases the lock even if the caller is cancelled.
 *
 * @return the result of [block], or `null` if the lock could not be acquired
 *   within [waitTimeoutMillis] (`null` waits indefinitely).
 * @throws RepositoryWriteLockLostException if TTL renewal fails while [block] runs.
 */
suspend fun <T> DistributedLockFactory.withRepositoryWriteLock(
    repositoryId: UUID,
    waitTimeoutMillis: Long?,
    block: suspend (RepositoryWriteLockHandle) -> T,
): T? = withRepositoryWriteLock(repositoryId, waitTimeoutMillis, Dispatchers.Default, block)

/**
 * [withRepositoryWriteLock] with the renewer running on [renewContext]. Renewal never runs on the
 * caller's dispatcher: a caller on a bounded pool (GitWorkDispatcher) whose threads are all busy
 * must not keep renewals from running. Tests pass their own dispatcher to control time;
 * [renewContext] must not contain a Job, which would detach the renewer from this call.
 */
internal suspend fun <T> DistributedLockFactory.withRepositoryWriteLock(
    repositoryId: UUID,
    waitTimeoutMillis: Long?,
    renewContext: CoroutineContext,
    block: suspend (RepositoryWriteLockHandle) -> T,
): T? {
    val key = RepositoryWriteLock.key(repositoryId)
    val lock = create(key)
    if (!lock.acquire(RepositoryWriteLock.TTL_MILLIS, waitTimeoutMillis, RepositoryWriteLock.RETRY_DELAY_MILLIS)) {
        return null
    }
    try {
        return coroutineScope {
            val handle = RepositoryWriteLockHandle(key, lock)
            val blockFinished = CompletableDeferred<Unit>()
            // UNDISPATCHED: the renewer is running before the block starts, so cancelling this
            // operation can never skip it.
            launch(renewContext, start = CoroutineStart.UNDISPATCHED) {
                // Renews until the block has actually returned, even if this operation was
                // cancelled: a cancelled push keeps running JGit's blocking receive (and its ref
                // updates) until it finishes, and the lock must not expire under it, or GC could
                // collect the objects those refs are about to point at. NonCancellable keeps the
                // loop alive through that; the signal below, not cancellation, ends it.
                withContext(NonCancellable) {
                    while (true) {
                        val done = withTimeoutOrNull(RepositoryWriteLock.RENEW_INTERVAL_MILLIS) { blockFinished.await() }
                        if (done != null) break
                        if (renewOrFalse(lock, key)) continue
                        // A block that finished while this renewal was in flight already did its
                        // work under the lock; only a block still running has lost it.
                        if (blockFinished.isCompleted) break
                        // Mark the handle first so an in-flight blocking section
                        // that consults ensureHeld() aborts even before the
                        // exception below cancels the block at a suspension point.
                        handle.markLost()
                        RepositoryWriteLock.log.error("Lost write lock {} while renewing; aborting operation", key)
                        throw RepositoryWriteLockLostException(key)
                    }
                }
            }
            try {
                block(handle)
            } finally {
                blockFinished.complete(Unit)
            }
        }
    } finally {
        withContext(NonCancellable) { lock.release() }
    }
}
