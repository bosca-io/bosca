package bosca.backup

import bosca.storage.service.ObjectStorageEntry
import kotlinx.serialization.Serializable

/**
 * Selected source objects and their observed metadata, grouped by logical source store.
 *
 * This selection contains no verified digests and does not establish database recovery coverage.
 * Retries retain this selection. Provider observations detect changes to current objects;
 * they do not freeze source bytes or select historical provider versions.
 */
@Serializable
data class ObjectBackupCaptureInventory(
    val id: String,
    val sourceId: String,
    val scope: String,
    val created: String,
    val stores: Map<String, List<ObjectStorageEntry>>,
) {
    /** Checks the complete selected inventory without accessing storage or progress. */
    fun validate() {
        ObjectBackupManifest(id, sourceId, scope, created, emptyList()).validate()
        for ((store, entries) in stores) {
            require(store.isNotBlank()) { "Logical source store is required" }
            val keys = mutableSetOf<String>()
            for (entry in entries) {
                require(entry.key.isNotBlank() && entry.key.split('/').all {
                    it.isNotEmpty() && it != "." && it != ".."
                }) { "Invalid selected source object key: ${entry.key}" }
                val version = entry.info.version?.takeIf { it.isNotBlank() && it != "null" }
                require(version != null || !entry.info.etag.isNullOrBlank() || entry.info.modified != null) {
                    "Source change metadata is required: $store/${entry.key}"
                }
                require(keys.add(entry.key)) {
                    "Duplicate selected source object: $store/${entry.key}"
                }
            }
        }
    }
}
