package bosca.content.timeevent.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Job definition for importing a PDF file into timeline events. When processed,
 * each page of the PDF is rendered as a PNG image, uploaded as a separate metadata
 * item, and linked to a newly created time event via a metadata relationship. The
 * resulting events are distributed evenly across the target media's duration.
 */
@Serializable
data class PdfTimelineImportJob(
    @Contextual
    val pdfMetadataId: UUID,
    @Contextual
    val targetMetadataId: UUID,
    val targetMetadataVersion: Int,
    val eventTypeId: String,
    val relationship: String,
    val durationMs: Long,
) : IJobDefinition
