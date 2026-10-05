package bosca.lock

/**
 * A distributed mutual-exclusion lock backed by an external store (e.g. Redis).
 *
 * All operations are idempotent from the caller's perspective and use a time-to-live (TTL)
 * to prevent deadlocks if the holder crashes without releasing.
 */
interface DistributedLock {

    /** Whether this lock instance currently holds the lock. */
    val isHeld: Boolean

    /**
     * Attempts to acquire the lock without waiting. Returns immediately.
     *
     * @param ttlMillis how long the lock is held before it auto-expires
     * @return `true` if the lock was acquired, `false` if it is already held by another caller
     */
    suspend fun tryAcquire(ttlMillis: Long): Boolean

    /**
     * Attempts to acquire the lock, retrying until success or timeout.
     *
     * @param ttlMillis how long the lock is held before it auto-expires
     * @param waitTimeoutMillis maximum time to spend retrying; `null` means retry indefinitely
     * @param retryDelayMillis delay between retry attempts
     * @return `true` if the lock was acquired within the timeout
     */
    suspend fun acquire(
        ttlMillis: Long,
        waitTimeoutMillis: Long? = null,
        retryDelayMillis: Long = 100,
    ): Boolean

    /**
     * Extends the TTL of an already-held lock.
     *
     * @param ttlMillis the new TTL from now
     * @return `true` if the renewal succeeded (i.e. the lock was still held)
     */
    suspend fun renew(ttlMillis: Long): Boolean

    /**
     * Releases the lock so other callers can acquire it.
     *
     * @return `true` if the lock was released, `false` if it was not held or had already expired
     */
    suspend fun release(): Boolean

    /**
     * Acquires the lock, executes [block], and releases the lock afterward.
     *
     * @param ttlMillis how long the lock is held before it auto-expires
     * @param waitTimeoutMillis maximum time to spend waiting for acquisition; `null` for indefinite
     * @param retryDelayMillis delay between retry attempts
     * @param block the suspend function to execute while holding the lock
     * @return the result of [block], or `null` if the lock could not be acquired
     */
    suspend fun <T> withLock(
        ttlMillis: Long,
        waitTimeoutMillis: Long? = null,
        retryDelayMillis: Long = 100,
        block: suspend () -> T,
    ): T?
}
