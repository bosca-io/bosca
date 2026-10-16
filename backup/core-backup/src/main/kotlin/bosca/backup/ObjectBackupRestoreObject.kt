package bosca.backup

/** A retained payload, its observed destination state and any existing restore checkpoint. */
data class ObjectBackupRestoreObject(
    val payload: BackupObject,
    val status: ObjectBackupRestoreObjectStatus,
    val progress: ObjectBackupProgress?,
)
