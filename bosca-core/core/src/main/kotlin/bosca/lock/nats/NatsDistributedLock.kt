package bosca.lock.nats

import bosca.lock.DistributedLock
import bosca.serialization.UUID
import io.nats.client.JetStreamApiException
import io.nats.client.KeyValue
import io.nats.client.MessageTtl
import io.nats.client.api.KeyValueOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class NatsDistributedLock(
    private val kv: KeyValue,
    name: String,
) : DistributedLock {

    private val key = lockKey(name)
    private val token: String = UUID.random().toString()
    private val expiresAt = AtomicLong(0L)

    override val isHeld: Boolean
        get() = System.currentTimeMillis() < expiresAt.get()

    override suspend fun tryAcquire(ttlMillis: Long): Boolean = withContext(Dispatchers.IO) {
        // Rounded up to whole seconds: the key must never expire on the server while isHeld still
        // reports the lock as ours.
        val expires = MessageTtl.seconds(max(1L, (ttlMillis + 999) / 1000).toInt())
        try {
            kv.create(key, token.toByteArray(), expires)
            expiresAt.set(System.currentTimeMillis() + ttlMillis)
            true
        } catch (e: JetStreamApiException) {
            if (e.apiErrorCode == WRONG_LAST_SEQUENCE) {
                false
            } else {
                throw e
            }
        } catch (e: Exception) {
            log.warn("Failed to acquire lock : ($key) : ${e.message}", e)
            false
        }
    }

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

    override suspend fun renew(ttlMillis: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val entry = kv.get(key) ?: return@withContext false
            if (entry.valueAsString != token) return@withContext false
            // TODO: Update when NATS supports updating the TTL per key
            val renewed = try {
                kv.update(key, token.toByteArray(), entry.revision)
                true
            } catch (e: JetStreamApiException) {
                // Wrong last sequence: another renewal by this same holder (a caller's own check and
                // a background renewer can overlap) moved the revision first. The lock is still ours
                // if the key still holds our token.
                if (e.apiErrorCode != WRONG_LAST_SEQUENCE) throw e
                kv.get(key)?.valueAsString == token
            }
            if (!renewed) return@withContext false
            expiresAt.set(System.currentTimeMillis() + ttlMillis)
            true
        } catch (e: Exception) {
            log.error("Failed to renew lock : ${e.message}", e)
            false
        }
    }

    override suspend fun release(): Boolean = withContext(Dispatchers.IO) {
        if (!isHeld) return@withContext false
        try {
            val entry = kv.get(key) ?: return@withContext false
            if (entry.valueAsString != token) return@withContext false
            // Only at the revision just read: had the key expired and been taken by another holder
            // in between, this fails (and is logged) instead of deleting that holder's lock.
            try {
                kv.purge(key, entry.revision)
            } catch (e: JetStreamApiException) {
                if (e.apiErrorCode != WRONG_LAST_SEQUENCE) throw e
                // This holder's own renewal moved the revision meanwhile (as in renew): purge at the
                // new revision if the key is still ours.
                val current = kv.get(key) ?: return@withContext false
                if (current.valueAsString != token) return@withContext false
                kv.purge(key, current.revision)
            }
            expiresAt.set(-1L)
            true
        } catch (e: Exception) {
            log.error("Failed to release lock : ${e.message}", e)
            false
        }
    }

    override suspend fun <T> withLock(
        ttlMillis: Long,
        waitTimeoutMillis: Long?,
        retryDelayMillis: Long,
        block: suspend () -> T,
    ): T? {
        // Not acquired within the wait: null, as the DistributedLock contract (and the Redis lock) says.
        if (!acquire(ttlMillis, waitTimeoutMillis, retryDelayMillis)) return null
        try {
            return block()
        } finally {
            // Released even when the holder was cancelled (release switches dispatchers, which a
            // cancelled coroutine cannot); release logs its own failures and returns false.
            withContext(NonCancellable) { release() }
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(NatsDistributedLock::class.java)

        /** The KV key for a lock [name]: KV keys may not contain ':' or '.'. Shared with forceRelease. */
        internal fun lockKey(name: String): String = name.replace(':', '-').replace('.', '-')

        /** JetStream's "wrong last sequence": the key is not at the revision a create or update expected. */
        private const val WRONG_LAST_SEQUENCE = 10071
    }
}