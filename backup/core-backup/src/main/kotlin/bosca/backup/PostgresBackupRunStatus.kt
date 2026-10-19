package bosca.backup

import bosca.db.annotation.DbMapper
import kotlinx.serialization.Serializable

/** Lifecycle of a native database backup request, independent of its abandoned attempts. */
@DbMapper(PostgresBackupRunStatusMapper::class)
@Serializable
enum class PostgresBackupRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
