package bosca.backup

import kotlinx.serialization.Serializable

/** Logical item totals; shared payload references each contribute their declared length. */
@Serializable
data class ObjectBackupRunSummary(
    val total: Long,
    val pending: Long,
    val running: Long,
    val completed: Long,
    val failed: Long,
    val totalBytes: Long,
    val verifiedBytes: Long,
)
