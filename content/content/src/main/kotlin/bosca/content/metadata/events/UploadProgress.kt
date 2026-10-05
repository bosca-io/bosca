package bosca.content.metadata.events

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Pub/sub channel for real-time upload progress updates during asynchronous
 * storage transfers (e.g., server to S3).
 */
const val METADATA_UPLOAD_PROGRESS_CHANNEL = "bosca.content.metadata.upload.progress"

/**
 * Progress update emitted during asynchronous storage uploads.
 *
 * Published to [METADATA_UPLOAD_PROGRESS_CHANNEL] as each chunk is written
 * to the storage backend, allowing clients to display real-time progress
 * for the server-side storage phase of an upload.
 */
@Serializable
data class UploadProgress(
    @Contextual
    val metadataId: UUID,
    val bytesUploaded: Long,
    val totalBytes: Long
)
