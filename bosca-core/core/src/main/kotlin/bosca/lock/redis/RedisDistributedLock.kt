package bosca.lock.redis

import bosca.lock.DistributedLock
import bosca.redis.RedisConnectionPool
import bosca.redis.RedisScriptExecutor
import bosca.serialization.UUID
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * A simple Redis-backed distributed lock.
 *
 * Semantics:
 * - Acquisition uses SET key value NX PX ttlMillis.
 * - Release and renew are guarded by Lua scripts that verify the owner token.
 * - Non-reentrant per-instance: each lock instance has a unique token; only the owner can release/renew.
 */
class RedisDistributedLock(
    private val connections: RedisConnectionPool,
    name: String,
    namespace: String = "locks",
) : DistributedLock {

    private val key = "$namespace:$name"
    private val token: String = UUID.random().toString()
    private val expiresAt = AtomicLong(0L)

    private val releaseExecutor = RedisScriptExecutor(connections, RELEASE_SCRIPT)
    private val renewExecutor = RedisScriptExecutor(connections, RENEW_SCRIPT)

    override val isHeld: Boolean
        get() = System.currentTimeMillis() < expiresAt.get()

    /**
     * Try to acquire the lock once with the given TTL in milliseconds.
     * Returns true if acquired, false otherwise.
     */
    @OptIn(ExperimentalLettuceCoroutinesApi::class)
    override suspend fun tryAcquire(ttlMillis: Long): Boolean {
        val connection = connections.connection()
        return try {
            val result = connection.coroutines().set(key, token, SetArgs().nx().px(ttlMillis))
            val acquired = result == "OK"
            if (acquired) expiresAt.set(System.currentTimeMillis() + ttlMillis)
            acquired
        } finally {
            connections.release(connection)
        }
    }

    /**
     * Acquire the lock, optionally waiting up to [waitTimeoutMillis] and retrying every [retryDelayMillis].
     * Returns true if acquired; false if the timeout elapsed without acquiring.
     */
    override suspend fun acquire(
        ttlMillis: Long,
        waitTimeoutMillis: Long?,
        retryDelayMillis: Long,
    ): Boolean {
        val start = System.nanoTime()
        while (true) {
            if (tryAcquire(ttlMillis)) return true
            if (waitTimeoutMillis != null) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs >= waitTimeoutMillis) return false
            }
            delay(retryDelayMillis)
        }
    }

    /**
     * Renew the lock TTL (in milliseconds) if still owned by this instance.
     * Returns true if renewed, false if not owner or key missing.
     */
    override suspend fun renew(ttlMillis: Long): Boolean {
        val result = renewExecutor.execute<Long>(ScriptOutputType.INTEGER, arrayOf(key), token, ttlMillis.toString())
        val renewed = result == 1L
        if (renewed) expiresAt.set(System.currentTimeMillis() + ttlMillis)
        return renewed
    }

    /**
     * Release the lock if still owned by this instance.
     * Returns true if released, false if not owner or already released.
     */
    override suspend fun release(): Boolean {
        if (!isHeld) return false
        val result = releaseExecutor.execute<Long>(ScriptOutputType.INTEGER, arrayOf(key), token)
        val released = result == 1L
        if (released) expiresAt.set(0L)
        return released
    }

    /**
     * Execute [block] under the lock. Returns null if the lock could not be acquired within the
     * optional [waitTimeoutMillis]. The lock is always released after [block] completes.
     */
    override suspend fun <T> withLock(
        ttlMillis: Long,
        waitTimeoutMillis: Long?,
        retryDelayMillis: Long,
        block: suspend () -> T,
    ): T? {
        val acquired = acquire(ttlMillis, waitTimeoutMillis, retryDelayMillis)
        if (!acquired) return null
        try {
            return block()
        } finally {
            // Released even when the holder was cancelled. A failed release must not replace the
            // block's own outcome: it is logged, and the lock then expires by its TTL.
            withContext(NonCancellable) {
                try {
                    release()
                } catch (e: Exception) {
                    log.warn("Failed to release lock {}", key, e)
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisDistributedLock::class.java)

        private const val RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
        """

        private const val RENEW_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            else
                return 0
            end
        """
    }
}