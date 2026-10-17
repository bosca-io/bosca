package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.serialization.UUID
import java.time.OffsetDateTime

/** An immutable request to publish one pending run version under a stable queue ID. */
data class ObjectBackupDispatch(
    val key: UUID,
    @ColumnName("run_key")
    val runKey: String,
    val version: Long,
    val created: OffsetDateTime,
)
