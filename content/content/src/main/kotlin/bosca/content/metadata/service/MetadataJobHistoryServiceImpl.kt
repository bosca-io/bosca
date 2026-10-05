package bosca.content.metadata.service

import bosca.content.metadata.events.METADATA_UPDATED_CHANNEL
import bosca.content.metadata.events.MetadataUpdated
import bosca.content.metadata.model.MetadataJobHistory
import bosca.content.metadata.repository.MetadataJobHistoryRepository
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
class MetadataJobHistoryServiceImpl(
    private val repository: MetadataJobHistoryRepository,
    private val pubsubService: PubSubService
) : MetadataJobHistoryService {

    private val logger = LoggerFactory.getLogger(MetadataJobHistoryServiceImpl::class.java)

    override suspend fun getLatestJobId(id: UUID, version: Int): JobHistoryId? = repository.getLatestJobId(id, version)

    override suspend fun addHistory(history: MetadataJobHistory): MetadataJobHistory {
        val history = repository.addHistory(history)
        pubsubService.publish(CHANNEL, MetadataJobHistory.serializer(), history)
        return history
    }

    override suspend fun getHistory(id: UUID, version: Int): List<MetadataJobHistory> {
        return repository.getHistory(id, version)
    }

    override suspend fun setStatus(id: UUID, version: Int, jobId: UUID, status: String) {
        val history = repository.setStatus(id, version, jobId, status) ?: return
        pubsubService.publish(CHANNEL, MetadataJobHistory.serializer(), history)
    }

    override suspend fun setComplete(id: UUID, version: Int, jobId: UUID, status: String, success: Boolean) {
        // Retry with exponential backoff in case the history INSERT hasn't
        // committed yet (race between queue publish and DB commit).
        var history = repository.setComplete(id, version, jobId, status, success)
        if (history == null) {
            var backoff = 200L
            for (i in 1..5) {
                delay(backoff.milliseconds)
                history = repository.setComplete(id, version, jobId, status, success)
                if (history != null) break
                backoff = (backoff * 2).coerceAtMost(2000L)
            }
        }
        if (history != null) {
            pubsubService.publish(CHANNEL, MetadataJobHistory.serializer(), history)
            // Also nudge the `metadata` GraphQL subscription (which listens on
            // METADATA_UPDATED_CHANNEL) so the admin UI's workflow query — and
            // with it the `activeJobs` list — refetches once this row is
            // closed. Without this, a multi-job parent can land `complete` in
            // the DB after the UI's last debounced refetch fired off a
            // fan-out child's own event, leaving the publish visibly stuck
            // until the user navigates away. Published directly (rather than
            // via `MetadataUpdated.dispatch()`) to skip the @JobEvent job
            // side-effects — this row closing isn't a reason to re-run
            // indexing or cache-invalidation.
            pubsubService.publish(
                METADATA_UPDATED_CHANNEL,
                MetadataUpdated.serializer(),
                MetadataUpdated(history.id, history.version),
            )
        } else {
            logger.warn("Metadata job history row not found after retries: id={}, version={}, jobId={}, status={}", id, version, jobId, status)
        }
    }

    override suspend fun waitForComplete(id: UUID, version: Int, jobId: UUID): MetadataJobHistory {
        return flow {
            val job = repository.getJob(id, version, jobId)
            if (job != null && job.complete != null) {
                emit(job)
                return@flow
            }
            pubsubService.subscribe(CHANNEL, MetadataJobHistory.serializer()).collect {
                if (it.message.id == id && it.message.version == version && it.message.jobId == jobId && it.message.complete != null) {
                    emit(it.message)
                }
            }
        }.first()
    }

    override suspend fun getActiveJobs(id: UUID, version: Int): List<MetadataJobHistory> {
        return repository.getActiveJobs(id, version)
    }

    companion object {

        const val CHANNEL = "metadata_job_history"
    }
}