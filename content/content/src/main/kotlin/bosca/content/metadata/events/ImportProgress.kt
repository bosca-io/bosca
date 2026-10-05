package bosca.content.metadata.events

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Pub/sub channel for real-time progress updates during asynchronous
 * URL import transfers (downloading from an external URL into storage).
 */
const val METADATA_IMPORT_PROGRESS_CHANNEL = "bosca.content.metadata.import.progress"

/**
 * Progress update emitted during asynchronous URL imports.
 *
 * Published to [METADATA_IMPORT_PROGRESS_CHANNEL] as content is downloaded
 * from the source URL and written to the storage backend, allowing clients
 * to display real-time progress for large file imports such as videos.
 */
@Serializable
data class ImportProgress(
    @Contextual
    val metadataId: UUID,
    val bytesDownloaded: Long,
    val totalBytes: Long,
)
