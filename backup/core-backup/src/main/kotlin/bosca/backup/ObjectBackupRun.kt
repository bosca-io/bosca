package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import java.time.OffsetDateTime

/** A fixed object inventory and its latest attempt; [result] preserves its terminal outcome. */
data class ObjectBackupRun(
    val key: String,
    @ColumnName("source_id")
    val sourceId: String,
    @ColumnName("destination_id")
    val destinationId: String,
    val scope: String,
    @property:DbMapper(JsonbMapper::class)
    val inventory: ObjectBackupManifest,
    /** Planned manifest path; publication is acknowledged only in the completed state. */
    val path: String,
    val status: ObjectBackupRunStatus = ObjectBackupRunStatus.PENDING,
    val attempts: Int = 0,
    val error: String? = null,
    @property:DbMapper(JsonbMapper::class)
    val result: ObjectBackupRunSummary? = null,
    val version: Long = 0,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val modified: OffsetDateTime = created,
)
