package bosca.backup

import bosca.db.annotation.ColumnName

/** One configured capture execution's durable ownership of its retained destination. */
data class ObjectBackupCaptureWriter(
    @ColumnName("capture_token")
    val key: String,
    @ColumnName("capture_key")
    val planKey: String,
    @ColumnName("storage_key")
    val storageKey: String,
)
