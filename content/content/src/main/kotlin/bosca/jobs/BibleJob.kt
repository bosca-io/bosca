package bosca.jobs

import bosca.bible.BibleFactory
import bosca.bible.bibleJson
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataSourceInput
import bosca.content.metadata.model.toInput
import bosca.content.metadata.service.MetadataService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException
import bosca.storage.service.ObjectStorageService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/**
 * Parses a stored DBL bundle and persists its Bible variants on the content runner.
 *
 * [initializeMetadata] is used by the direct bundle-import route to populate the
 * language and DBL metadata that are not known until the bundle is parsed. Existing
 * metadata reprocessing leaves those user-editable fields unchanged while ensuring
 * the domain type attribute identifies the item as a Bible.
 */
@Serializable
data class BibleProcessJob(
    val id: UUID,
    val version: Int,
    val initializeMetadata: Boolean = false,
    val publish: Boolean = false,
    val principalId: UUID? = null,
) : IJobDefinition

@JobDefinition(BibleProcessJob::class, JobQueueNames.contentJobQueue, "bible-process")
class BibleProcessExecutor(
    private val metadataService: MetadataService,
    private val objectService: ObjectStorageService,
    private val bibleFactory: BibleFactory,
    private val securityService: SecurityService,
) : AbstractJobExecutor<BibleProcessJob>(BibleProcessJob.serializer()) {

    override suspend fun getLockId(): String {
        val job = getJobDefinition()
        return "${job.id}-${job.version}"
    }

    override suspend fun execute() {
        val job = getJobDefinition()
        var metadata = metadataService.getById(job.id, job.version)
            ?: throw FailException("Metadata not found: ${job.id}")
        val path = objectService.getPath(metadata)
        val bibles = objectService.getInputStream(path).use { bibleFactory.getBibles(it) }
        if (bibles.isEmpty()) {
            throw FailException("No Bibles found in DBL bundle for metadata ${job.id}")
        }

        if (job.initializeMetadata) {
            val primary = bibles.first()
            val threeLetterTag = primary.metadata.language.iso
            val attributes = JsonObject(
                bibleJson.encodeToJsonElement(primary.metadata.asSerializable()).jsonObject +
                    ("type" to JsonPrimitive("Bible")),
            )
            metadata = metadataService.edit(
                metadata.id,
                MetadataInput(
                    name = metadata.name,
                    languageTag = threeLetterTag,
                    contentType = "bosca/v-bible",
                    contentLength = metadata.contentLength,
                    metadataType = metadata.type,
                    parentId = metadata.parentId,
                    locked = metadata.locked,
                    attributes = attributes,
                    labels = metadata.labels,
                    source = MetadataSourceInput(
                        id = metadata.sourceId,
                        identifier = metadata.sourceIdentifier,
                        sourceUrl = metadata.sourceUrl,
                    ),
                    searchable = metadata.searchable,
                    syncVariantCollections = metadata.syncVariantCollections,
                    syncVariantRelationships = metadata.syncVariantRelationships,
                ),
            )
        } else {
            metadataService.mergeAttributes(
                metadata,
                JsonObject(mapOf("type" to JsonPrimitive("Bible"))),
            )
        }

        bibles.forEachIndexed { index, bible ->
            log.info("Processing Bible: {}", bible.metadata)
            metadataService.setBible(metadata, bible.toInput(index == 0))
        }

        if (job.publish) {
            val authentication = job.principalId?.let { securityService.impersonate(it) }
                ?: securityService.impersonate("sa")
            val principal = authentication.principal().asPrincipal()
            metadata = metadataService.getById(job.id, job.version)
                ?: throw FailException("Metadata not found after Bible processing: ${job.id}")
            metadata = metadataService.setReady(metadata, principal)
            metadataService.setState(metadata, "published", "", principal)
        }
    }

    companion object {

        private val log = LoggerFactory.getLogger(BibleProcessJob::class.java)
    }
}
