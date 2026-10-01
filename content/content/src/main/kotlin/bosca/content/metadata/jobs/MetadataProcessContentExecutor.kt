package bosca.content.metadata.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.image.service.ImageService
import bosca.content.metadata.service.MetadataService
import bosca.content.video.service.VideoService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException

@JobDefinition(MetadataProcessContentJob::class, JobQueueNames.contentJobQueue, "metadata-process-content")
class MetadataProcessContentExecutor(
    private val metadataService: MetadataService,
    private val imageService: ImageService,
    private val videoService: VideoService,
) : AbstractJobExecutor<MetadataProcessContentJob>(MetadataProcessContentJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val metadata = metadataService.getById(job.id, job.version) ?: throw FailException("Metadata not found")
        val typeParts = metadata.contentType.lowercase().split("/")
        val type = typeParts.firstOrNull() ?: throw FailException("Content type not found")
        when (type) {
            "image" -> {
                if (metadata.uploaded == null) return
                imageService.optimize(metadata)
            }
            "video" -> {
                if (metadata.uploaded == null) return
                videoService.process(metadata)
            }
            else -> {
                val hasImageRelationships = metadataService.getRelationships(metadata.id).any {
                    val metadataRel = metadataService.getById(it.metadataId2)
                    if (metadataRel == null) {
                        return@any false
                    } else {
                        return@any metadataRel.uploaded != null
                    }
                }
                if (hasImageRelationships) {
                    imageService.optimize(metadata)
                }
            }
        }
    }
}
