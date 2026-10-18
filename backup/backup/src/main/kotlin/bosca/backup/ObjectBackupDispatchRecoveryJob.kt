package bosca.backup

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Reconciles saved pending requests after interrupted publication to the queue. */
@Serializable
data class ObjectBackupDispatchRecoveryJob(val pageSize: Int = 100) : IJobDefinition {
    init {
        require(pageSize > 0) { "Object backup dispatch recovery page size must be positive" }
    }

    companion object {
        const val NAME = "object-backup-dispatch-recovery"
    }
}
