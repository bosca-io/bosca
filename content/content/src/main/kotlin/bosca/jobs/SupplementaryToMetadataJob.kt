package bosca.jobs

import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.service.MetadataService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.job
import bosca.sharedqueue.jobs.jobQueue
import bosca.slug.service.SlugService
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import bosca.storage.service.upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Serializable
data class SupplementaryToMetadataJob(
    val supplementaryId: UUID? = null,
    val relationship: String? = null
) : IJobDefinition

@JobDefinition(SupplementaryToMetadataJob::class, JobQueueNames.contentJobQueue, "supplementary-to-metadata")
class SupplementaryToMetadataJobExecutor(
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService,
    private val slugService: SlugService
) : AbstractJobExecutor<SupplementaryToMetadataJob>(SupplementaryToMetadataJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        val files = mutableListOf<File>()
        val supplementaryId = job.supplementaryId ?: suspend {
            val parent = job().getParentId()?.let {
                jobQueue().getJob(it) { it }
            }
            parent?.getContext()?.jsonObject["supplementaryId"]?.jsonPrimitive?.contentOrNull?.let { UUID.parse(it) }
        }() ?: error("Missing supplementaryId")
        val supplementary = metadataService.getSupplementaryById(supplementaryId) ?: error("Missing supplementary")
        try {
            val metadata = metadataService.getById(supplementary.metadataId) ?: error("Missing metadata")
            val file = withContext(Dispatchers.IO) { File.createTempFile("job-${UUID.random()}", ".data") }
            objectStorageService.download(metadata, supplementaryId).use { data ->
                file.outputStream().use {
                    data.copyTo(it)
                }
                Unit
            }
            files.add(file)
            val slug = slugService.getMetadataSlug(metadata.id)
            val newMetadata = metadataService.add(
                null, null, MetadataInput(
                    name = supplementary.name,
                    languageTag = metadata.languageTag,
                    contentType = supplementary.contentType ?: "application/octet-stream",
                    contentLength = file.length(),
                    metadataType = metadata.type,
                    slug = "$slug-${supplementary.key}"
                )
            )
            objectStorageService.upload(newMetadata, null, file)
            metadataService.setUploaded(newMetadata.id, newMetadata.contentType, file.length())
            job.relationship?.let {
                metadataService.addRelationship(
                    MetadataRelationshipInput(
                        id1 = metadata.id,
                        id2 = newMetadata.id,
                        relationship = it,
                        attributes = JsonObject(emptyMap())
                    )
                )
            }
            setContext(
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(newMetadata.id.toString()),
                        "version" to JsonPrimitive(newMetadata.version.toString())
                    )
                )
            )
        } finally {
            files.forEach { it.delete() }
        }
    }
}