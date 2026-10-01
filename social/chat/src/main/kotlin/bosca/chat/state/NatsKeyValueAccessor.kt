package bosca.chat.state

import bosca.nats.NatsConnectionPool
import io.nats.client.KeyValue
import io.nats.client.api.KeyValueConfiguration
import io.nats.client.api.StorageType
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Lazily resolves a [KeyValue] handle on the shared NATS connection,
 * creating the bucket on the first call if it doesn't already exist.
 *
 * The chat KV stores all follow the same shape — a single bucket per
 * concern (reactions, typing, presence, read-state) shared across
 * channels with composite keys — so this helper centralises the
 * bucket-or-create dance instead of every store carrying its own copy.
 *
 * Storage type, TTL, and replication are bucket-level decisions and
 * are passed in by the caller because they encode the durability
 * trade-off for that particular state. Reactions and read-state are
 * file-backed and untimed; typing and presence are memory-backed with
 * a short TTL so a quietly-disconnected client's stale "is typing"
 * marker auto-expires.
 */
internal class NatsKeyValueAccessor(
    private val nats: NatsConnectionPool,
    private val bucket: String,
    private val configure: KeyValueConfiguration.Builder.() -> Unit,
) {
    private val mutex = Mutex()

    @Volatile
    private var kv: KeyValue? = null

    suspend fun get(): KeyValue {
        kv?.let { return it }
        return mutex.withLock {
            kv?.let { return@withLock it }
            val k = withContext(Dispatchers.IO) {
                val connection = nats.systemConnection()
                try {
                    connection.keyValue(bucket)
                } catch (e: Exception) {
                    log.info("Creating KV bucket '{}': {}", bucket, e.message)
                    val mgmt = connection.keyValueManagement()
                    runCatching {
                        val builder = KeyValueConfiguration.builder().name(bucket).storageType(StorageType.File)
                        builder.configure()
                        mgmt.create(builder.build())
                    }.onFailure {
                        // Another instance may have raced us to bucket creation.
                        log.warn("Failed to create KV bucket '{}' (likely already exists): {}", bucket, it.message)
                    }
                    connection.keyValue(bucket)
                }
            }
            kv = k
            k
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NatsKeyValueAccessor::class.java)
    }
}

/** Marker used by stores that need to encode "key exists" with no payload value. */
internal val EMPTY_VALUE: ByteArray = ByteArray(0)

/** Default TTL for ephemeral state (typing indicators, presence). */
internal val EPHEMERAL_TTL: Duration = Duration.ofMinutes(5)
