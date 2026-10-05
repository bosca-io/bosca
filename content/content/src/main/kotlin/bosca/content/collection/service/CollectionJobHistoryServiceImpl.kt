package bosca.content.collection.service

import bosca.content.collection.events.COLLECTION_UPDATED_CHANNEL
import bosca.content.collection.events.CollectionUpdated
import bosca.content.collection.model.CollectionJobHistory
import bosca.content.collection.repository.CollectionJobHistoryRepository
import bosca.content.metadata.service.MetadataJobHistoryServiceImpl.Companion.CHANNEL
import bosca.content.transition.model.JobHistoryId
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

@ServiceImplementation
class CollectionJobHistoryServiceImpl(
    private val repository: CollectionJobHistoryRepository,
    private val pubsubService: PubSubService
) : CollectionJobHistoryService {

    private val logger = LoggerFactory.getLogger(CollectionJobHistoryServiceImpl::class.java)

    override suspend fun getLatestJobId(id: UUID): JobHistoryId? = repository.getLatestJobId(id)

    override suspend fun addHistory(history: CollectionJobHistory): CollectionJobHistory {
        val history = repository.addHistory(history)
        pubsubService.publish(CHANNEL, CollectionJobHistory.serializer(), history)
        return history
    }

    override suspend fun getHistory(id: UUID): List<CollectionJobHistory> {
        return repository.getHistory(id)
    }

    override suspend fun setStatus(id: UUID, jobId: UUID, status: String) {
        val history = repository.setStatus(id, jobId, status) ?: return
        pubsubService.publish(CHANNEL, CollectionJobHistory.serializer(), history)
    }

    override suspend fun setComplete(id: UUID, jobId: UUID, status: String, success: Boolean) {
        // The history row may not exist yet if the worker picked up the
        // job before the Transitioner's addHistory INSERT committed (race
        // between queue publish and DB commit). Retry with exponential
        // backoff; if the row never appears, this is a child job with no
        // history entry — log a warning and skip.
        var history = repository.setComplete(id, jobId, status, success)
        if (history == null) {
            var backoff = 200L
            for (i in 1..5) {
                delay(backoff.milliseconds)
                history = repository.setComplete(id, jobId, status, success)
                if (history != null) break
                backoff = (backoff * 2).coerceAtMost(2000L)
            }
        }
        if (history != null) {
            pubsubService.publish(CHANNEL, CollectionJobHistory.serializer(), history)
            // Also nudge the `collection` GraphQL subscription (which listens
            // on COLLECTION_UPDATED_CHANNEL) so the admin UI's workflow query
            // — and with it the `activeJobs` list — refetches once this row
            // is closed. See the metadata counterpart for the full rationale;
            // published directly (rather than via `CollectionUpdated.dispatch()`)
            // to skip the @JobEvent side-effect jobs.
            pubsubService.publish(
                COLLECTION_UPDATED_CHANNEL,
                CollectionUpdated.serializer(),
                CollectionUpdated(history.id, history.languageTag),
            )
        } else {
            logger.warn("Collection job history row not found after retries: collection={}, jobId={}, status={}", id, jobId, status)
        }
    }

    override suspend fun waitForComplete(id: UUID, jobId: UUID): CollectionJobHistory {
        return flow {
            val job = repository.getJob(id, jobId)
            if (job != null && job.complete != null) {
                emit(job)
                return@flow
            }
            pubsubService.subscribe(CHANNEL, CollectionJobHistory.serializer()).collect {
                if (it.message.id == id && it.message.jobId == jobId && it.message.complete != null) {
                    emit(it.message)
                }
            }
        }.first()
    }

    override suspend fun getActiveJobs(id: UUID): List<CollectionJobHistory> {
        return repository.getActiveJobs(id)
    }
}