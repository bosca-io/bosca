package bosca.backup

import kotlinx.serialization.Serializable

/** Associates a declared logical store with one bound storage identity. */
@Serializable
data class ObjectBackupStoreMapping(val store: String, val storageKey: String)
