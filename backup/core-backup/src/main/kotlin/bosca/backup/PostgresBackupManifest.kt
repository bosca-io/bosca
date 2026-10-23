package bosca.backup

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Provenance and byte identity of a native PostgreSQL database artifact, independent of object backups.
 * [created] observes the exported boundary and [completed] records pg_dump completion, both on the
 * database clock. [snapshotId] is provenance only: it cannot be imported after capture releases its
 * transaction. [snapshot] records PostgreSQL's logical visibility boundary independently of transfer time.
 * [contentInventory] binds reference evidence from that same view to this artifact; it does not
 * establish retained asset coverage. [requiredKeys] maps known key names to the populated encrypted
 * tables that require them in that view. It contains no key values and does not prove key availability
 * or cover dependencies of arbitrary modules.
 */
@Serializable
data class PostgresBackupManifest(
    val id: String,
    val sourceId: String,
    val database: String,
    val created: String,
    val completed: String,
    val snapshotId: String,
    val snapshot: String,
    val serverVersion: String,
    val serverVersionNumber: Int,
    val encoding: String,
    val extensions: Map<String, String>,
    val length: Long,
    val sha256: String,
    @Required
    val payloadPath: String = "database.dump",
    @Required
    val version: Int = 2,
    @Required
    val format: String = "bosca-postgres-backup",
    val contentInventory: PostgresBackupContentInventory? = null,
    @Required
    val requiredKeys: Map<String, List<String>> = emptyMap(),
) {
    /** Rejects unsupported formats and incomplete database recovery metadata. */
    fun validate() {
        contentInventory?.validate()
        require(format == "bosca-postgres-backup" && version == 2) { "Unsupported PostgreSQL backup format/version" }
        require(payloadPath == "database.dump") { "Unsupported PostgreSQL backup payload path: $payloadPath" }
        require(listOf(id, sourceId, database, snapshotId, snapshot, serverVersion, encoding).all { it.isNotBlank() }) {
            "PostgreSQL backup identity and boundary metadata are required"
        }
        require(serverVersionNumber > 0) { "PostgreSQL server version is required" }
        val boundary: Instant
        val end: Instant
        try {
            boundary = Instant.parse(created)
            end = Instant.parse(completed)
        } catch (failure: DateTimeParseException) {
            throw IllegalArgumentException("Invalid PostgreSQL backup timestamp", failure)
        }
        require(!end.isBefore(boundary)) { "PostgreSQL backup completed before its recorded boundary" }
        require(length > 0 && sha256.matches(Regex("[0-9a-f]{64}"))) { "PostgreSQL dump length and SHA-256 are required" }
        require(extensions.all { (name, version) -> name.isNotBlank() && version.isNotBlank() }) {
            "PostgreSQL extension names and versions are required"
        }
        require(requiredKeys.all { (name, tables) ->
            name.isNotBlank() &&
                tables.isNotEmpty() &&
                tables.all { it.isNotBlank() } &&
                tables.distinct().size == tables.size
        }) { "Required encryption keys need names and distinct nonblank source tables" }
    }
}
