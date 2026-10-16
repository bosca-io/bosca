package bosca.backup

/** A verified object inventory and destination observations in the inventory's declared order. */
data class ObjectBackupRestoreInspection(
    val inventory: ObjectBackupManifest,
    val objects: List<ObjectBackupRestoreObject>,
)
