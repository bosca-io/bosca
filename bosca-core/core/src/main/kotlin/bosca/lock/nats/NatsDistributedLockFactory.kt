package bosca.lock.nats

import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.nats.NatsConnectionPool
import io.nats.client.JetStream
import io.nats.client.KeyValue
import io.nats.client.api.KeyValueConfiguration
import io.nats.client.api.StorageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration

class NatsDistributedLockFactory(
    private val nats: NatsConnectionPool
) : DistributedLockFactory {

    private val kvMutex = Mutex()
    private var store: Pair<KeyValue, JetStream>? = null

    private suspend fun getStore(): Pair<KeyValue, JetStream> {
        store?.let { return it }
        return kvMutex.withLock {
            store?.let { return@withLock it }
            val k = withContext(Dispatchers.IO) {
                val connection = nats.systemConnection()
                try {
                    connection.keyValue("locks")
                } catch (e: Exception) {
                    log.info("Creating locks bucket : ${e.message}")
                    val management = connection.keyValueManagement()
                    try {
                        management.create(
                            KeyValueConfiguration.builder()
                                .name("locks")
                                .storageType(StorageType.Memory)
                                .limitMarker(Duration.ofMinutes(30))
                                .ttl(Duration.ofMinutes(30))
                                .build()
                        )
                    } catch (e: Exception) {
                        log.warn("Failed to create locks bucket", e)
                        // Ignore if already created by another instance
                    }
                    connection.keyValue("locks")
                } to connection.jetStream()
            }
            store = k
            k
        }
    }

    override suspend fun create(name: String): DistributedLock {
        val (kv, jetStream) = getStore()
        return NatsDistributedLock(kv, jetStream, name)
    }

    override suspend fun forceRelease(name: String): Boolean = withContext(Dispatchers.IO) {
        val key = NatsDistributedLock.lockKey(name)
        try {
            val kv = getStore().first
            val entry = kv.get(key)
            if (entry == null) return@withContext false
            kv.delete(key)
            kv.purge(key)
            true
        } catch (e: Exception) {
            log.warn("Failed to force-release lock: $key : ${e.message}", e)
            false
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NatsDistributedLockFactory::class.java)
    }
}
