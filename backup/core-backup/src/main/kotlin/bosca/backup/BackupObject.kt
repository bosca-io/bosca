package bosca.backup

import kotlinx.serialization.Serializable

/**
 * An exact source object revision and the verified payload retained in backup storage.
 *
 * [store] identifies a logical source namespace and [key] is its object key. [revision] must
 * identify the same source bytes for the lifetime of the capture. [sha256] is the lowercase
 * digest of the entire payload, and [payloadPath] is its retained destination object key.
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
