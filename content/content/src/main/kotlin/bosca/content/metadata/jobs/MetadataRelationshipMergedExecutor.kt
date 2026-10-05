package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.db.transaction
import bosca.events.eventManager
import bosca.queue.annotations.JobDefinition
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

@JobDefinition(MetadataRelationshipMergedJob::class, JobQueueNames.contentJobQueue, "metadata-relationship-merged")
class MetadataRelationshipMergedExecutor(
    metadataService: MetadataService,
) : MetadataSyncExecutor<MetadataRelationshipMergedJob>(metadataService, MetadataRelationshipMergedJob.serializer()) {

    override suspend fun syncVariantRelationship(metadata: Metadata, relationship: ContentRelationship) {
        if (!metadata.syncVariantRelationships) {
            metadataService.markCollaborationRelationshipsDirty(metadata.id)
            return
        }
        val variants = getVariants(metadata)
        transaction {
            eventManager().disabled {
                variants.filter { it.syncVariantRelationships }.forEach { variant ->
                    transaction {
                        try {
                            metadataService.mergeAttributes(variant.id, relationship.id2, relationship.relationship, relationship.attributes ?: JsonObject(emptyMap()))
                            metadataService.markCollaborationRelationshipsDirty(variant.id)
                            MetadataIndexJob(
                                id = variant.id,
                                version = variant.version,
                            ).enqueue()
                        } catch (e: Exception) {
                            log.error("error: failed to add metadata relationship", e)
                        }
                    }
                    metadataService.markCollaborationRelationshipsDirty(metadata.id)
                    MetadataIndexJob(
                        id = metadata.id,
                        version = metadata.version,
                    ).enqueue()
                }
            }
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(MetadataRelationshipMergedExecutor::class.java)
    }
}
