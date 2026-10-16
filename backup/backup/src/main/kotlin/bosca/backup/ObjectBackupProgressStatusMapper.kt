package bosca.backup

import bosca.db.mapper.EnumMapper

/** Maps the progress status to and from the database's lowercase text values. */
object ObjectBackupProgressStatusMapper : EnumMapper<ObjectBackupProgressStatus>(
    { ObjectBackupProgressStatus.valueOf(it.uppercase()) },
)
