package bosca.workops.service

import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.workops.jobs.SpecContextSyncJob
import bosca.workops.jobs.enqueue
import bosca.workops.repository.SpecRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

@Serializable
internal data class MetadataUpdated(val id: UUID, val version: Int = 0)

class SpecDocumentSyncListener(
    private val pubSubService: PubSubService,
    private val specRepository: SpecRepository,
    private val connectionPool: ConnectionPool,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                try {
                    pubSubService.subscribe(
                        "bosca.content.metadata.updated",
                        MetadataUpdated.serializer(),
                    ).collect { msg ->
                        process(msg.message.id)
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Spec document sync listener failed, retrying in 5s: {}", e.message)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    internal suspend fun process(metadataId: UUID) {
        val mgr = connectionPool.connection()
        try {
            withContext(mgr.asCoroutineContext()) {
                val spec = specRepository.getByMetadataId(metadataId) ?: return@withContext
                SpecContextSyncJob(specId = spec.id).enqueue()
            }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    internal fun close() {
        scope.cancel()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SpecDocumentSyncListener::class.java)
    }
}
