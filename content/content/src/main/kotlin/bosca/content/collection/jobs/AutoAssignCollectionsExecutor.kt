package bosca.content.collection.jobs

import bosca.configuration.service.ConfigurationService
import bosca.configuration.service.getValueAs
import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.MetadataService
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.slug.service.SlugService
import kotlinx.serialization.json.*

@JobDefinition(AutoAssignCollectionsJob::class, JobQueueNames.contentJobQueue, "auto-assign-collections")
class AutoAssignCollectionsExecutor(
    private val collectionService: CollectionService,
    private val metadataService: MetadataService,
    private val configurationService: ConfigurationService,
    private val slugService: SlugService,
    private val json: Json
) : AbstractJobExecutor<AutoAssignCollectionsJob>(AutoAssignCollectionsJob.serializer()) {

    override suspend fun execute() {
        val jobConfig = getJobDefinition()
        val autoConfig = configurationService.getValueAs<AutoAssignCollectionsConfiguration>(
            if (jobConfig.version == null) "auto.assign.collection.collection" else "auto.assign.collection.metadata", json)
            ?: return

        val targetCollectionIds = mutableSetOf<UUID>()
        val attributes = if (jobConfig.version != null) {
            metadataService.getById(jobConfig.id, jobConfig.version)?.attributes
        } else {
            collectionService.getById(jobConfig.id)?.attributes
        } ?: return

        if (attributes !is JsonObject) return

        for ((key, value, slug) in autoConfig.attributes) {
            val element = attributes[key] ?: continue
            val values = when (element) {
                is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(element.contentOrNull)
                else -> emptyList()
            }
            if (values.contains(value)) {
                slugService.get(slug)?.collectionId?.let { targetCollectionIds.add(it) }
            }
        }

        for (collectionId in targetCollectionIds) {
            if (jobConfig.version != null) {
                collectionService.addMetadataItem(collectionId, jobConfig.id, null)
            } else {
                collectionService.addCollectionItem(collectionId, jobConfig.id, null)
            }
        }
    }
}
