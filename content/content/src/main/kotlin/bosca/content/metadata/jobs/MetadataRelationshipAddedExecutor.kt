package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataRelationship
import bosca.content.metadata.service.MetadataService
import bosca.content.model.ContentRelationship
import bosca.db.transaction
import bosca.events.eventManager
import bosca.queue.annotations.JobDefinition
import org.slf4j.LoggerFactory

@JobDefinition(MetadataRelationshipAddedJob::class, JobQueueNames.contentJobQueue, "metadata-relationship-added")
class MetadataRelationshipAddedExecutor(
    metadataService: MetadataService,
) : MetadataSyncExecutor<MetadataRelationshipAddedJob>(metadataService, MetadataRelationshipAddedJob.serializer()) {

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
                            val relatedVariantId = metadataService.getLanguageVariantById(relationship.id2, variant.languageTag)
                            val relationship = MetadataRelationship(
                                metadataId1 = variant.id,
                                metadataId2 = relatedVariantId ?: relationship.id2,
                                relationship = relationship.relationship,
                                attributes = relationship.attributes
                            )
                            metadataService.addRelationship(relationship)
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

        private val log = LoggerFactory.getLogger(MetadataRelationshipAddedExecutor::class.java)
    }
}
