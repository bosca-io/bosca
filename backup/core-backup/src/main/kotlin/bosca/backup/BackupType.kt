package bosca.backup

import kotlinx.serialization.Serializable

/** The independently restorable artifact made available by a completed backup. */
@Serializable
enum class BackupType {
    POSTGRES,
    OBJECTS,
}
