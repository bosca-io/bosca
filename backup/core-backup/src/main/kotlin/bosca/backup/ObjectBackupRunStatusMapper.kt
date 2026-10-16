package bosca.backup

import bosca.db.mapper.EnumMapper

/** Maps run states to the database's lowercase text values. */
object ObjectBackupRunStatusMapper : EnumMapper<ObjectBackupRunStatus>(
    { ObjectBackupRunStatus.valueOf(it.uppercase()) },
)
