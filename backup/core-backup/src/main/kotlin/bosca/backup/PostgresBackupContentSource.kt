package bosca.backup

import kotlinx.serialization.Serializable

/** Fixed content-source binding and capture scope; storage credentials remain in configuration. */
@Serializable
data class PostgresBackupContentSource(
    val store: String,
    val storageKey: String,
    val scope: String,
) {
    /** Requires explicit logical store, configured storage identity and checkpoint scope. */
    fun validate() {
        require(store.isNotBlank() && storageKey.isNotBlank() && scope.isNotBlank()) {
            "Content source store, storage identity and scope are required"
        }
    }
}
