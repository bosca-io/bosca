package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import java.time.OffsetDateTime

/** Durable intent and the latest verified outcome for one source revision and destination. */
data class ObjectBackupProgress(
    val key: String,
    @ColumnName("source_id")
    val sourceId: String,
    @ColumnName("destination_id")
    val destinationId: String,
    val scope: String,
    val operation: ObjectBackupOperation,
    @property:DbMapper(JsonbMapper::class)
    val payload: BackupObject,
    val status: ObjectBackupProgressStatus = ObjectBackupProgressStatus.PENDING,
    val attempts: Int = 0,
    @ColumnName("verified_bytes")
    val verifiedBytes: Long = 0,
    val error: String? = null,
    val version: Long = 0,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val modified: OffsetDateTime = created,
    /** Provider identity whose exact bytes were checksum-verified at [verified]. */
    @ColumnName("verified_version")
    val verifiedVersion: String? = null,
    /** Full-check time for the provider identity; absent when no strong identity was recorded. */
    val verified: OffsetDateTime? = null,
)
