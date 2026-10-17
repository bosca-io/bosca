package bosca.backup

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Executes an already-prepared run; inventories and credentials remain outside the queue payload. */
@Serializable
data class ObjectBackupRunJob(val key: String) : IJobDefinition {
    init {
        require(key.isNotBlank()) { "Object backup run key is required" }
    }
}
