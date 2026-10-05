package bosca.content.transition.jobs

import bosca.content.collection.model.ICollection
import bosca.content.collection.service.CollectionJobHistoryService
import bosca.content.collection.service.CollectionService
import bosca.content.metadata.service.MetadataJobHistoryService
import bosca.content.metadata.service.MetadataService
import bosca.content.transition.service.Transitioner
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobListener
import bosca.sharedqueue.jobs.JobStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory

/**
 * Closes out the `metadata_job_history` / `collection_job_history` row for a
 * transition job when the job reaches a terminal state (COMPLETE or FAILED).
 *
 * When the job succeeds and the content item still has a pending workflow state,
 * this listener also finalizes the state transition by calling [setPendingStateComplete].
 * Single transition executors (e.g. [MetadataTransitionExecutor]) clear the pending state
 * themselves during execution, so the reload check prevents double-firing. Multi-jobs,
 * whose children perform work but never finalize the state, rely on this listener to
 * complete the transition.
 *
 * Because multi-job transitions never route through [ContentTransitionLogic], this
 * listener is also responsible for the advertised→published chain on that path:
 * after finalizing a transition into "advertised" (or when a redelivered
 * notification finds the item already settled there with nothing pending), it
 * invokes [ContentTransitionLogic.chainToPublished] so the publish transition is
 * scheduled for the item's `published` epoch. A pending state whose
 * `workflowStateValid` is still in the future is left untouched — that transition
 * belongs to the delayed job that will fire at the scheduled time, not to a
 * duplicate notification arriving early.
 *
 * Reads the content item identity (`id`, `version`) from the job definition
 * and determines the content type from either the `type` field in the
 * definition or the executor class (metadata vs collection).
 *
 * Idempotent: a second update to an already-closed row is a harmless no-op.
 */
class UpdateContentJobHistoryOnCompleteListener(
    private val metadataJobHistoryService: MetadataJobHistoryService,
    private val collectionJobHistoryService: CollectionJobHistoryService,
    private val metadataService: MetadataService,
    private val collectionService: CollectionService,
    private val securityService: SecurityService,
    private val transitioner: Transitioner,
) : JobListener {

    override suspend fun onStatusChanged(job: Job, status: JobStatus, errorMessage: String?) {
        // Only terminal states close the row, mirroring the terminal-status guard
        // used by the platform's other terminal listeners (e.g. NotifyJobStatusListener).
        val success = when (status) {
            JobStatus.COMPLETE -> true
            // The terminal failure closes the row as failed; a non-terminal FAILED (still retrying) does not.
            JobStatus.FAILED_AND_COMPLETE -> false
            else -> return
        }
        // A successful job is only finished once it and all of its children are
        // complete, so multi-job parents stay open until their children finish.
        // A failed job is terminal immediately; gating it on isFullyComplete()
        // (which is only ever true for COMPLETE) is what previously left a failed
        // job's history row open ("pending") forever.
        if (success && !job.isFullyComplete()) return

        val definition = job.getDefinition() as? JsonObject ?: return
        val idString = (definition["id"] as? JsonPrimitive)?.content ?: return
        val id = try {
            UUID.parse(idString)
        } catch (e: Exception) {
            log.warn("unable to parse id '{}' on job {}", idString, job.getId(), e)
            return
        }

        val statusMessage = if (success) "Complete" else (errorMessage ?: "Failed")
        val type = resolveContentType(definition)

        val isMultiJob = job.getChildren().isNotEmpty()

        when (type) {
            "metadata" -> {
                val version = (definition["version"] as? JsonPrimitive)?.intOrNull
                if (version == null) {
                    log.warn("missing version on metadata job {}", job.getId())
                    return
                }
                metadataJobHistoryService.setComplete(id, version, job.getId(), statusMessage, success)
                if (success && isMultiJob) completeMetadataPendingState(id, version)
            }

            "collection" -> {
                collectionJobHistoryService.setComplete(id, job.getId(), statusMessage, success)
                if (success && isMultiJob) completeCollectionPendingState(id, definition)
            }

            else -> {
                log.warn("unknown content type '{}' on job {}", type, job.getId())
            }
        }
    }

    private suspend fun completeMetadataPendingState(id: UUID, version: Int) {
        metadataService.removeFromCache(id, version)
        var metadata = metadataService.getById(id, version) ?: return
        if (metadata.workflowStatePendingId == null && metadata.workflowStateId != "advertised") return
        if (isStillScheduled(metadata.workflowStateValid)) {
            log.info(
                "skipping pending state completion for metadata {} v{}; transition to '{}' is scheduled for {}",
                id, version, metadata.workflowStatePendingId, metadata.workflowStateValid
            )
            return
        }
        val auth = securityService.impersonate("sa")
        if (metadata.workflowStatePendingId != null) {
            metadata = metadataService.setPendingStateComplete(metadata, "Transition Complete", auth.principal().asPrincipal())
        }
        if (metadata.workflowStateId == "advertised" && metadata.workflowStatePendingId == null) {
            ContentTransitionLogic.chainToPublished(metadata, auth, transitioner, languageTag = null)
        }
    }

    private suspend fun completeCollectionPendingState(id: UUID, definition: JsonObject) {
        collectionService.removeFromCache(id)
        val languageTag = (definition["languageTag"] as? JsonPrimitive)?.content
        var collection: ICollection = if (languageTag != null) {
            collectionService.getLanguageVariant(id, languageTag)
        } else {
            collectionService.getById(id)
        } ?: return
        if (collection.workflowStatePendingId == null && collection.workflowStateId != "advertised") return
        if (isStillScheduled(collection.workflowStateValid)) {
            log.info(
                "skipping pending state completion for collection {}; transition to '{}' is scheduled for {}",
                id, collection.workflowStatePendingId, collection.workflowStateValid
            )
            return
        }
        val auth = securityService.impersonate("sa")
        if (collection.workflowStatePendingId != null) {
            collection = collectionService.setPendingStateComplete(collection, "Transition Complete", auth.principal().asPrincipal())
        }
        if (collection.workflowStateId == "advertised" && collection.workflowStatePendingId == null) {
            ContentTransitionLogic.chainToPublished(collection, auth, transitioner, languageTag)
        }
    }

    /**
     * True when a pending transition is scheduled for a future time (beyond a
     * 1-second jitter tolerance, mirroring the transition executors) — the delayed
     * job owns that completion, not this notification.
     */
    private fun isStillScheduled(valid: OffsetDateTime?): Boolean {
        valid ?: return false
        return valid.toInstant().toEpochMilli() - System.currentTimeMillis() > 1_000
    }

    private fun resolveContentType(definition: JsonObject): String? {
        (definition["type"] as? JsonPrimitive)?.content?.let { return it }
        return if (definition.containsKey("version")) "metadata" else "collection"
    }

    companion object {
        private val log = LoggerFactory.getLogger(UpdateContentJobHistoryOnCompleteListener::class.java)
    }
}
