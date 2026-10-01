package bosca.lock

/**
 * Factory for creating named [DistributedLock] instances.
 *
 * Each unique [name] should map to the same logical lock across all processes,
 * enabling cross-process mutual exclusion.
 */
interface DistributedLockFactory {

    /**
     * Creates a [DistributedLock] identified by [name].
     * Multiple calls with the same name may return the same or equivalent lock instance.
     */
    suspend fun create(name: String): DistributedLock

    /**
     * Unconditionally deletes the lock identified by [name], regardless of which instance holds it.
     *
     * This is an administrative operation intended for recovery scenarios (e.g. clearing stale locks
     * left by crashed workers). Normal code paths should use [DistributedLock.release] instead.
     *
     * @return `true` if the lock existed and was removed, `false` if no such lock was found
     */
    suspend fun forceRelease(name: String): Boolean
}