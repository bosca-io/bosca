package bosca.backup

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Portable inventory for one source and declared object-backup scope.
 *
 * [created] is the inventory creation time; database recovery-point coverage is established
 * separately.
 */
@Serializable
data class ObjectBackupManifest(
    val id: String,
    val sourceId: String,
    val scope: String,
    val created: String,
    val objects: List<BackupObject>,
    @Required
    val version: Int = 1,
    @Required
    val format: String = "bosca-object-backup",
) {
    /** Checks whether the manifest describes an unambiguous inventory of retained payloads. */
    fun validate() {
        require(format == "bosca-object-backup") {
            "Unsupported object backup format: $format"
        }
        require(version == 1) {
            "Unsupported object backup version: $version"
        }

        require(id.isNotBlank()) {
            "Object backup identity is required"
        }
        require(sourceId.isNotBlank()) {
            "Object backup source identity is required"
        }
        require(scope.isNotBlank()) {
            "Object backup scope is required"
        }

        try {
            Instant.parse(created)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Invalid object backup creation time: $created", error)
        }

        val keys = mutableSetOf<Pair<String, String>>()
        val payloads = mutableMapOf<String, Pair<Long, String>>()

        for (entry in objects) {
            entry.validate()
            require(keys.add(entry.store to entry.key)) {
                "Duplicate object key: ${entry.store}/${entry.key}"
            }

            val identity = entry.length to entry.sha256
            val previous = payloads[entry.payloadPath]
            require(previous == null || previous == identity) {
                "Conflicting payload identity: ${entry.payloadPath}"
            }
            payloads[entry.payloadPath] = identity
        }
    }
}
