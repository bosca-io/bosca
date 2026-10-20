package bosca.backup

import kotlinx.serialization.Serializable

/**
 * A source object selection and the verified payload retained in backup storage.
 *
 * [store] identifies a logical source namespace and [key] is its existing object key.
 * [revision] is the backup checkpoint's change identity, derived from observed source metadata
 * or a selected provider version. An observation does not freeze an unversioned source.
 * [sha256] and [length] identify the captured bytes independently of source metadata;
 * [payloadPath] is their retained destination object key.
 */
@Serializable
data class BackupObject(
    val store: String,
    val key: String,
    val revision: String,
    val length: Long,
    val sha256: String,
    val payloadPath: String,
) {
    /** Checks that this revision identifies a payload with a portable destination key. */
    fun validate() {
        require(
            store.isNotBlank() &&
                key.isNotBlank() &&
                revision.isNotBlank()
        ) {
            "Object store, key, and revision identity are required"
        }
        require(length >= 0) {
            "Invalid object length for $key: $length"
        }
        require(SHA256.matches(sha256)) {
            "Invalid SHA-256 digest for $key"
        }
        require(
            payloadPath.split('/').all {
                it.isNotEmpty() && it != "." && it != ".."
            }
        ) {
            "Invalid backup payload path: $payloadPath"
        }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
