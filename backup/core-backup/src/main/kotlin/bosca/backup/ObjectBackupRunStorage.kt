package bosca.backup

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import java.time.OffsetDateTime

/** Fixed backup storage and logical source/target mappings for one prepared object run. */
data class ObjectBackupRunStorage(
    val key: String,
    @ColumnName("backup_key")
    val backupKey: String,
    @property:DbMapper(JsonbMapper::class)
    val stores: Map<String, String>,
    val created: OffsetDateTime = OffsetDateTime.now(),
)
