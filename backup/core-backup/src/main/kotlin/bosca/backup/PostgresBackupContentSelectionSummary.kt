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
) {
    /** Validates explicit nonnegative outcomes and their relation to the distinct selected paths. */
    fun validate() {
        inventory.validate()
        require(counts.keys == PostgresBackupContentSelectionStatus.entries.toSet()) {
            "Every content selection outcome must be counted"
        }
        require(counts.values.all { it >= 0 }) { "Content selection counts must not be negative" }
        val selected = counts.getValue(PostgresBackupContentSelectionStatus.SELECTED)
        val paths = inventory.stores.values.sumOf { it.size.toLong() }
        require(selected >= paths && (selected == 0L) == (paths == 0L)) {
            "Selected reference count must account for the selected paths"
        }
    }
}
