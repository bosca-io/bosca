package bosca.backup

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Executes an already-prepared run; inventories and credentials remain outside the queue payload. */
@Serializable
data class ObjectBackupRunJob(val key: String, val version: Long) : IJobDefinition {
    init {
        require(key.isNotBlank()) { "Object backup run key is required" }
        require(version >= 0) { "Object backup run version must not be negative" }
    }
}
