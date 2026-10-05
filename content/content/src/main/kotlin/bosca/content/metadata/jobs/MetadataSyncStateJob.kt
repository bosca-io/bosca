package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.timeevent.service.TimeEventService
import bosca.content.transition.jobs.MetadataTransitionExecutor
import bosca.content.transition.model.BeginTransitionInput
import bosca.content.transition.service.Transitioner
import bosca.events.deferredEvents
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class MetadataSyncStateJob(
    val id: UUID,
    val version: Int
) : IJobDefinition

/**
 * Synchronizes the publish state of a metadata item to all its related metadata via relationships.
 *
 * When a metadata item with [Metadata.syncVariantRelationships] enabled transitions to the published
 * state, this executor propagates public visibility flags and initiates publish transitions for each
 * related metadata item that is not yet published. This includes both standard metadata relationships
 * and metadata linked through time event relationships (e.g. PDF page images on a media timeline).
 * Related metadata items in the "pending" state are first transitioned through to "draft" before
 * being published.
 */
@JobDefinition(MetadataSyncStateJob::class, JobQueueNames.contentJobQueue, "metadata-sync-state")
class MetadataSyncStateExecutor(
    private val metadataService: MetadataService,
    private val timeEventService: TimeEventService,
    private val transitioner: Transitioner,
    private val securityService: SecurityService
) : AbstractJobExecutor<MetadataSyncStateJob>(MetadataSyncStateJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        metadataService.removeFromCache(job.id, job.version)
        val metadata = metadataService.getById(job.id, job.version) ?: return
        if (metadata.syncVariantRelationships && metadata.isPublished) {
            val failures = mutableListOf<Pair<UUID, Exception>>()

            metadataService.getRelationships(metadata.id).forEach { rel ->
                try {
                    syncRelationship(metadata, rel.metadataId2)
                } catch (e: Exception) {
                    log.error("failed to sync relationship state: {} -> {}", metadata.id, rel.metadataId2, e)
                    failures.add(rel.metadataId2 to e)
                }
            }
            timeEventService.getRelatedMetadataIds(metadata.id, metadata.version).forEach { relatedId ->
                try {
                    syncRelationship(metadata, relatedId)
                } catch (e: Exception) {
                    log.error("failed to sync time event relationship state: {} -> {}", metadata.id, relatedId, e)
                    failures.add(relatedId to e)
                }
            }

            if (failures.isNotEmpty()) {
                val ids = failures.joinToString { it.first.toString() }
                throw RuntimeException("Failed to sync ${failures.size} relationship(s) for ${metadata.id}: $ids", failures.first().second)
            }
        }
    }

    private suspend fun syncRelationship(metadata: Metadata, relatedId: UUID) {
        var relMetadata = metadataService.getById(relatedId) ?: return
        deferredEvents {
            if (metadata.public && !relMetadata.public) {
                metadataService.setPublic(relMetadata, true)
            }
            if (metadata.publicContent && !relMetadata.publicContent) {
                metadataService.setPublicContent(relMetadata, true)
            }
            if (metadata.publicSupplementary && !relMetadata.publicSupplementary) {
                metadataService.setPublicSupplementary(relMetadata, true)
            } else if (!relMetadata.publicSupplementary && relMetadata.contentType.startsWith("image/")) {
                metadataService.setPublicSupplementary(relMetadata, true)
            }
        }
        if (!relMetadata.isPublished) {
            val sa = securityService.impersonate("sa")
            if (relMetadata.ready == null) {
                relMetadata = metadataService.setReady(relMetadata, sa.principal().asPrincipal())
            }
            if (relMetadata.workflowStatePendingId != null) {
                relMetadata = MetadataTransitionExecutor.execute(metadataService, transitioner, relMetadata, sa) as Metadata
            }
            if (relMetadata.workflowStateId == "pending") {
                relMetadata = metadataService.setPendingState(
                    relMetadata,
                    "draft",
                    status = "Moving from pending to draft",
                    principal = sa.principal().asPrincipal()
                )
                relMetadata = MetadataTransitionExecutor.execute(metadataService, transitioner, relMetadata, sa) as Metadata
            }
            transitioner.beginTransition(
                sa,
                BeginTransitionInput(
                    metadataId = relMetadata.id,
                    version = relMetadata.version,
                    stateId = "published",
                    status = "Syncing Publish State"
                )
            )
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(MetadataSyncStateExecutor::class.java)
    }
}
