package bosca.backup

import bosca.db.annotation.DbMapper
import kotlinx.serialization.Serializable

/** Distinguishes retained backup payloads from restored application objects. */
@DbMapper(ObjectBackupOperationMapper::class)
@Serializable
enum class ObjectBackupOperation {
    BACKUP,
    RESTORE,
}
