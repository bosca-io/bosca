package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import java.time.OffsetDateTime

/** Fixed request intent and the last attempt's state; only a completed request has a receipt. */
data class PostgresBackupRun(
    val key: String,
    @ColumnName("source_id")
    val sourceId: String,
    @ColumnName("storage_key")
    val storageKey: String,
    val prefix: String,
    val status: PostgresBackupRunStatus = PostgresBackupRunStatus.PENDING,
    val attempts: Int = 0,
    @ColumnName("writer_token")
    val writerToken: String? = null,
    val error: String? = null,
    @property:DbMapper(JsonbMapper::class)
    val result: PostgresBackupReceipt? = null,
    val version: Long = 0,
    val created: OffsetDateTime = OffsetDateTime.now(),
    val modified: OffsetDateTime = created,
)
