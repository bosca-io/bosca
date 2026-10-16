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
)
