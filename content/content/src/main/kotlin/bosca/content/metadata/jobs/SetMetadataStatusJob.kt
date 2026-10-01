package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.service.MetadataService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Serializable

@Serializable
data class SetMetadataStatusJob(
    val id: UUID,
    val version: Int,
    val public: Boolean?,
    val publicContent: Boolean?,
    val publicSupplementary: Boolean?,
    val type: String = "metadata"
) : IJobDefinition

@JobDefinition(SetMetadataStatusJob::class, JobQueueNames.contentJobQueue, "set-metadata-status")
class SetMetadataStatusJobExecutor(
    private val metadataService: MetadataService,
) : AbstractJobExecutor<SetMetadataStatusJob>(SetMetadataStatusJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val metadata = metadataService.getById(job.id, job.version) ?: return
        job.public?.let {
            metadataService.setPublic(metadata, it)
        }
        job.publicContent?.let {
            metadataService.setPublicContent(metadata, it)
        }
        job.publicSupplementary?.let {
            metadataService.setPublicSupplementary(metadata, it)
        }
    }
}