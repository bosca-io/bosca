package bosca.content.collection.jobs

import bosca.content.collection.service.CollectionService
import bosca.content.configuration.JobQueueNames
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

@Serializable
data class SetCollectionStatusJob(
    val id: UUID,
    val public: Boolean?,
    val publicList: Boolean?,
    val publicSupplementary: Boolean?,
    val languageTag: String? = null,
    val type: String = "collection"
) : IJobDefinition {

}

@JobDefinition(SetCollectionStatusJob::class, JobQueueNames.contentJobQueue, "set-collection-status")
class SetCollectionStatusJobExecutor(
    private val collectionService: CollectionService,
) : AbstractJobExecutor<SetCollectionStatusJob>(SetCollectionStatusJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val collection = collectionService.getById(job.id) ?: return
        job.public?.let {
            collectionService.setPublic(collection.id, it, job.languageTag)
        }
        job.publicList?.let {
            collectionService.setPublicList(collection.id, it, job.languageTag)
        }
        job.publicSupplementary?.let {
            collectionService.setPublicSupplementary(collection.id, it, job.languageTag)
        }
    }
}