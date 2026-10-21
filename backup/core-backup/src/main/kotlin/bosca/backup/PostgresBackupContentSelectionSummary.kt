package bosca.backup

import kotlinx.serialization.Serializable

/**
 * Observations for a database artifact: [inventory] uses that artifact's ID/source identity and
 * contains each selected path once. Counts cover every saved reference, including gaps and
 * duplicates. This is capture input, not a retained artifact or completed database recovery point.
 */
@Serializable
data class PostgresBackupContentSelectionSummary(
    val inventory: ObjectBackupCaptureInventory,
    val counts: Map<PostgresBackupContentSelectionStatus, Long>,
)
