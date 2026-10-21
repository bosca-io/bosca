package bosca.backup

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * Portable evidence linking a retained native artifact and its captured content in the same backup
 * store. Counts include gaps. Inspection records retained integrity at export, not continuing
 * storage health or proof that object bytes match the database's transaction boundary.
 * No completion or restorable-recovery-point status is implied by this report.
 */
@Serializable
data class PostgresBackupContentReport(
    val databasePath: String,
    val database: PostgresBackupManifest,
    val objects: ObjectBackupManifest,
    val selectionCounts: Map<PostgresBackupContentSelectionStatus, Long>,
    val inspection: PostgresBackupContentInspectionSummary,
    @Required
    val version: Int = 1,
    @Required
    val format: String = "bosca-postgres-content-report",
) {
    /** Checks artifact identities, distinct retained locations and complete reference outcome counts. */
    fun validate() {
        require(version == 1 && format == "bosca-postgres-content-report") { "Unsupported PostgreSQL content report" }
        database.validate()
        objects.validate()
        val inventory = requireNotNull(database.contentInventory) { "Database artifact has no content-reference inventory" }
        require(databasePath.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Invalid retained database path: $databasePath"
        }
        require(database.id == objects.id && database.sourceId == objects.sourceId) {
            "Database and content capture identities differ"
        }
        require(inspection.databaseId == database.id && inspection.objectManifestId == objects.id) {
            "Inspection identifies different artifacts"
        }
        require(inspection.store.isNotBlank() && objects.objects.all { it.store == inspection.store }) {
            "Report requires one matching logical content store"
        }
        val reserved = listOf(database.payloadPath, inventory.path, "manifest.json", "intent.json")
            .map { "$databasePath/$it" }.toSet()
        require(objects.objects.none { it.payloadPath in reserved }) { "Asset payload conflicts with a native artifact component" }
        require(selectionCounts.keys == PostgresBackupContentSelectionStatus.entries.toSet()) {
            "Every content selection outcome must be counted"
        }
        require(inspection.counts.keys == PostgresBackupContentStatus.entries.toSet()) {
            "Every content inspection outcome must be counted"
        }
        for (counts in listOf(selectionCounts.values, inspection.counts.values)) {
            var total = 0L
            for (count in counts) {
                require(count >= 0 && count <= inventory.count - total) { "Invalid content reference outcome counts" }
                total += count
            }
            require(total == inventory.count) { "Content outcome counts differ from the saved reference count" }
        }
    }
}
