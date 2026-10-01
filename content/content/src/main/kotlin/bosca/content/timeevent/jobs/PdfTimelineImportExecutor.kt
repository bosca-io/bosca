package bosca.content.timeevent.jobs

import bosca.attributes.AttributeType
import bosca.content.configuration.JobQueueNames
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataRelationshipInput
import bosca.content.metadata.service.MetadataService
import bosca.content.timeevent.events.TIME_EVENT_CHANGED_CHANNEL
import bosca.content.timeevent.events.TimeEventChanged
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.service.TimeEventService
import bosca.pubsub.PubSubService
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.sharedqueue.jobs.AbstractJobExecutor
import bosca.sharedqueue.jobs.FailException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.download
import bosca.storage.service.upload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.slf4j.LoggerFactory
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToLong

/**
 * Processes a PDF file by rendering each page as a PNG image, storing each image
 * as a separate metadata item, and creating timeline events on the target media
 * that are evenly distributed across its duration. Each timeline event is linked
 * to its corresponding page image through a metadata relationship.
 */
@JobDefinition(PdfTimelineImportJob::class, JobQueueNames.contentJobQueue, "pdf-timeline-import")
class PdfTimelineImportExecutor(
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService,
    private val timeEventService: TimeEventService,
    private val pubSubService: PubSubService,
    private val securityService: SecurityService,
) : AbstractJobExecutor<PdfTimelineImportJob>(PdfTimelineImportJob.serializer()) {

    override suspend fun getLockId(): String {
        val job = getJobDefinition()
        return "${job.targetMetadataId}-${job.targetMetadataVersion}"
    }

    override suspend fun execute() {
        val job = getJobDefinition()
        val tempFiles = mutableListOf<File>()
        try {
            val pdfMetadata = metadataService.getById(job.pdfMetadataId)
                ?: throw FailException("PDF metadata not found: ${job.pdfMetadataId}")

            if (pdfMetadata.contentType != "application/pdf") {
                throw FailException("Content is not a PDF (type: ${pdfMetadata.contentType})")
            }

            val targetMetadata = metadataService.getById(job.targetMetadataId, job.targetMetadataVersion)
                ?: throw FailException("Target metadata not found: ${job.targetMetadataId}")

            clearPriorImport(job)

            val pdfTempFile = withContext(Dispatchers.IO) {
                val f = File.createTempFile("pdf-source-", ".pdf")
                tempFiles.add(f)
                objectStorageService.download(pdfMetadata).use { inputStream ->
                    f.outputStream().use { out -> inputStream.copyTo(out) }
                }
                f
            }

            // Load the PDF once for both page count and rendering
            val (numPages, pageFiles) = withContext(Dispatchers.IO) {
                Loader.loadPDF(pdfTempFile).use { document ->
                    val count = document.numberOfPages
                    val renderer = PDFRenderer(document)
                    val files = (0 until count).map { pageIndex ->
                        val image = renderer.renderImageWithDPI(pageIndex, 150f, ImageType.RGB)
                        File.createTempFile("pdf-page-", ".png").also {
                            tempFiles.add(it)
                            ImageIO.write(image, "png", it)
                        }
                    }
                    count to files
                }
            }

            // Look up the event type's attribute template to find the METADATA attribute
            // that defines the key and relationship for attaching page images.
            val typeAttributes = timeEventService.getTypeAttributes(job.eventTypeId)
            val metadataAttr = typeAttributes.firstOrNull { it.type == AttributeType.METADATA }
            val attrKey = metadataAttr?.key
            val attrRelationship = metadataAttr?.configuration
                ?.jsonObject?.get("relationship")?.jsonPrimitive?.contentOrNull
                ?: job.relationship

            val interval = if (numPages > 1) job.durationMs.toDouble() / numPages else 0.0
            val sa = securityService.impersonate("sa")

            for (pageIndex in 0 until numPages) {
                val tempFile = pageFiles[pageIndex]

                val pageMetadata = metadataService.add(
                    null,
                    null,
                    MetadataInput(
                        name = "${pdfMetadata.name} - Page ${pageIndex + 1}",
                        contentLength = tempFile.length(),
                        contentType = "image/png",
                        languageTag = targetMetadata.languageTag,
                        searchable = false,
                    )
                )
                objectStorageService.upload(pageMetadata, null, tempFile)
                metadataService.setUploaded(pageMetadata.id, "image/png", tempFile.length())

                metadataService.addRelationship(
                    MetadataRelationshipInput(
                        id1 = pageMetadata.id,
                        id2 = job.pdfMetadataId,
                        relationship = "original",
                        attributes = JsonObject(emptyMap()),
                    )
                )

                val startMs = (pageIndex * interval).roundToLong()
                val endMs = if (numPages > 1) ((pageIndex + 1) * interval).roundToLong() else job.durationMs

                // Build the time event attributes JSON, setting the page metadata
                // reference under the attribute key defined by the type's attribute
                // template. The value is an object with "id" and "attributes" to match
                // the format used by the frontend attribute editor.
                val eventAttributes = if (attrKey != null) {
                    JsonObject(mapOf(attrKey to JsonObject(mapOf(
                        "id" to JsonPrimitive(pageMetadata.id.toString()),
                        "attributes" to JsonObject(emptyMap()),
                    ))))
                } else {
                    null
                }

                val timeEvent = timeEventService.addTimeEvent(
                    job.targetMetadataId,
                    job.targetMetadataVersion,
                    TimeEventInput(
                        type = job.eventTypeId,
                        startOffsetMs = startMs,
                        endOffsetMs = endMs,
                        sort = pageIndex,
                        attributes = eventAttributes,
                    )
                )

                timeEventService.addMetadataRelationship(
                    timeEvent.id,
                    TimeEventMetadataRelationshipInput(
                        metadataId = pageMetadata.id,
                        relationship = attrRelationship,
                        attributes = JsonObject(emptyMap()),
                    ),
                    notify = false,
                )

                timeEventService.addMetadataRelationship(
                    timeEvent.id,
                    TimeEventMetadataRelationshipInput(
                        metadataId = job.pdfMetadataId,
                        relationship = "original",
                        attributes = JsonObject(emptyMap()),
                    ),
                    notify = false,
                )

                metadataService.setReady(pageMetadata, sa.principal().asPrincipal())

                log.info("Created timeline event for page {} of {}", pageIndex + 1, numPages)
            }

            pubSubService.publish(
                TIME_EVENT_CHANGED_CHANNEL,
                TimeEventChanged.serializer(),
                TimeEventChanged(
                    metadataId = job.targetMetadataId,
                    metadataVersion = job.targetMetadataVersion,
                    eventCount = numPages,
                )
            )

            log.info("PDF timeline import complete: {} pages processed for metadata {}", numPages, job.targetMetadataId)
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    /**
     * Removes any time events of the job's event type that already exist on the target
     * timeline, along with the orphaned page-image metadata they reference. This makes
     * the executor convergent: partial failures, manual deletions, or re-imports of a
     * different PDF all resolve to a clean slate before the new import begins.
     */
    private suspend fun clearPriorImport(job: PdfTimelineImportJob) {
        val existingEvents = timeEventService.getTimeEventsByType(
            job.targetMetadataId, job.targetMetadataVersion, job.eventTypeId,
        )
        if (existingEvents.isEmpty()) return

        val pageMetadataIds = existingEvents.flatMap { event ->
            timeEventService.getMetadataRelationships(event.id)
                .filter { it.relationship != "original" }
                .map { it.metadataId }
        }.distinct()

        timeEventService.deleteTimeEventsByType(
            job.targetMetadataId, job.targetMetadataVersion, job.eventTypeId,
        )

        for (id in pageMetadataIds) {
            val metadata = metadataService.getById(id) ?: continue
            metadataService.delete(metadata)
        }

        log.info(
            "Cleared {} prior events and {} page images for target {} v{} before re-import",
            existingEvents.size, pageMetadataIds.size, job.targetMetadataId, job.targetMetadataVersion,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(PdfTimelineImportExecutor::class.java)
    }
}
