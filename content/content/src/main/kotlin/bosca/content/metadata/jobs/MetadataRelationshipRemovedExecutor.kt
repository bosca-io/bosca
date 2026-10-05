package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.db.transaction
import bosca.events.eventManager
import bosca.queue.annotations.JobDefinition
import org.slf4j.LoggerFactory

@JobDefinition(MetadataRelationshipRemovedJob::class, JobQueueNames.contentJobQueue, "metadata-relationship-removed")
class MetadataRelationshipRemovedExecutor(
    metadataService: MetadataService,
) : MetadataSyncExecutor<MetadataRelationshipRemovedJob>(metadataService, MetadataRelationshipRemovedJob.serializer()) {

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
                            metadataService.getLanguageVariantById(relationship.id2, variant.languageTag)?.let {
                                metadataService.removeRelationship(variant.id, it, relationship.relationship)
                            }
                            metadataService.removeRelationship(variant.id, relationship.id2, relationship.relationship)
                            metadataService.markCollaborationRelationshipsDirty(variant.id)
                            MetadataIndexJob(
                                id = variant.id,
                                version = variant.version,
                            ).enqueue()
                        } catch (e: Exception) {
                            log.error("error: failed to add metadata relationship", e)
                        }
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

    companion object {

        private val log = LoggerFactory.getLogger(MetadataRelationshipRemovedExecutor::class.java)
    }
}
