package bosca.backup

import bosca.db.mapper.EnumMapper

/** Maps object operations to lowercase database text. */
object ObjectBackupOperationMapper : EnumMapper<ObjectBackupOperation>(
    { ObjectBackupOperation.valueOf(it.uppercase()) },
)
