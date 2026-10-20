package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.storage.service.ObjectStorageInfo
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
    /** Destination observation saved after checking the retained payload's complete length and digest. */
    @ColumnName("verified_info")
    @property:DbMapper(JsonbMapper::class)
    val verifiedInfo: ObjectStorageInfo? = null,
    /** Last recorded full checksum-check time; metadata-only checks do not advance it. */
    val verified: OffsetDateTime? = null,
)
