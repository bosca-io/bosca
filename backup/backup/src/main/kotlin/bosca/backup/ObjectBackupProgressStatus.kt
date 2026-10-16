package bosca.backup

import bosca.db.annotation.DbMapper
import kotlinx.serialization.Serializable

/** The persisted lifecycle of one object backup revision at its destination. */
@DbMapper(ObjectBackupProgressStatusMapper::class)
@Serializable
enum class ObjectBackupProgressStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
