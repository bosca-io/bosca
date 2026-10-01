package bosca.counter.nats

import bosca.counter.Counter
import bosca.nats.NatsConnectionPool
import io.nats.client.JetStreamApiException
import io.nats.client.api.StreamConfiguration
import io.nats.client.impl.Headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration as JavaDuration

/**
 * NATS-backed [Counter] using JetStream's server-side message counter: publishing to the key's subject
 * with the `Nats-Incr` header atomically adds to that subject's running total (server-side, so hot keys
 * never thrash a client compare-and-set loop), and the publish ack carries the new total. The value is
 * read back from the subject's last message. All keys share one counter-enabled stream, whose `maxAge`
 * expires idle buckets. NATS server 2.11+ (client 2.25.2 exposes `allowMessageCounter`).
 */
class NatsCounter(
    private val pool: NatsConnectionPool,
    private val json: Json,
) : Counter {

    private val streamMutex = Mutex()
    @Volatile
    private var streamReady = false

    override suspend fun increment(key: String, by: Long): Long = withContext(Dispatchers.IO) {
        ensureStream()
        val headers = Headers().add(INCR_HEADER, by.toString())
        val ack = pool.systemConnection().jetStream().publish(subject(key), headers, EMPTY_BODY)
        ack.getVal()?.toLongOrNull() ?: 0L
    }

    override suspend fun get(key: String): Long = withContext(Dispatchers.IO) {
        ensureStream()
        try {
            parseTotal(pool.systemConnection().getStreamContext(STREAM).getLastMessage(subject(key)).data)
        } catch (e: JetStreamApiException) {
            0L // no message on the subject yet → the counter is zero
        }
    }

    override suspend fun get(keys: List<String>): Map<String, Long> = keys.associateWith { get(it) }

    private fun subject(key: String): String = "$PREFIX.$key"

    /** The counter total is stored as `{"val":"<n>"}`; fall back to a bare number for safety. */
    private fun parseTotal(data: ByteArray?): Long {
        if (data == null || data.isEmpty()) return 0L
        val text = String(data, Charsets.UTF_8)
        return runCatching { json.parseToJsonElement(text).jsonObject["val"]?.jsonPrimitive?.content?.toLong() }
            .getOrNull() ?: text.trim().toLongOrNull() ?: 0L
    }

    /** Create the counter-enabled stream once per process (idempotent under a startup race). */
    private suspend fun ensureStream() {
        if (streamReady) return
        streamMutex.withLock {
            if (streamReady) return
            val management = pool.systemConnection().jetStreamManagement()
            val exists = runCatching { management.streamNames.contains(STREAM) }.getOrDefault(false)
            if (!exists) {
                val config = StreamConfiguration.builder()
                    .name(STREAM)
                    .subjects("$PREFIX.>")
                    .allowMessageCounter(true)
                    .maxAge(JavaDuration.ofDays(RETENTION_DAYS))
                    .build()
                runCatching { management.addStream(config) } // a concurrent creator already won → fine
            }
            streamReady = true
        }
    }

    companion object {
        private const val STREAM = "BOSCA_COUNTERS"
        private const val PREFIX = "bosca.counter"
        private const val INCR_HEADER = "Nats-Incr"
        private const val RETENTION_DAYS = 7L
        private val EMPTY_BODY = ByteArray(0)
    }
}
