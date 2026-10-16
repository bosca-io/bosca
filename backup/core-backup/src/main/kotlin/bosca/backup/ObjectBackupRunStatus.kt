package bosca.backup

import bosca.db.annotation.DbMapper
import kotlinx.serialization.Serializable

/** Lifecycle of a declared object inventory, separate from a full database snapshot. */
@DbMapper(ObjectBackupRunStatusMapper::class)
@Serializable
enum class ObjectBackupRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
}
