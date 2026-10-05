package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import java.time.OffsetDateTime

/**
 * Persistent record tracking the lifecycle and outcome of a backup operation.
 * Rows are stored in the `backups` database table and updated as the
 * backup job progresses through its phases.
 */
data class BackupRecord(
    /** Unique identifier for this backup operation. */
    val id: UUID,
    /** Current status: pending, running, completed, or failed. */
    val status: String,
    /** Object storage path of the completed archive, null until the backup finishes. */
    val path: String?,
    /** Error message if the backup failed, null on success. */
    val error: String?,
    /** Whether binary files from object storage are included in this backup. */
    @ColumnName("include_files")
    val includeFiles: Boolean,
    /** Metadata item holding the backup archive, enabling download via the standard content API. */
    @ColumnName("metadata_id")
    val metadataId: UUID? = null,
    /** Timestamp when the backup was initiated. */
    val created: OffsetDateTime,
    /** Timestamp of the most recent status update. */
    val modified: OffsetDateTime
)
