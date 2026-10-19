package bosca.backup

import bosca.db.mapper.EnumMapper

/** Maps native database request states to the journal's lowercase text values. */
object PostgresBackupRunStatusMapper : EnumMapper<PostgresBackupRunStatus>(
    { PostgresBackupRunStatus.valueOf(it.uppercase()) },
)
