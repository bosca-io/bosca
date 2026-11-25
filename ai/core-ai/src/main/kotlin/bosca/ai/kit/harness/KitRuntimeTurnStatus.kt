package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Durable prompt state; INTERRUPTED records an uncertain native outcome that must not be replayed. */
@Serializable
enum class KitRuntimeTurnStatus {
    QUEUED,
    SENT,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    FAILED;

    /** Whether the turn has finished and is no longer eligible for submission. */
    val terminal: Boolean get() = when (this) {
        QUEUED, SENT -> false
        COMPLETED, CANCELLED, INTERRUPTED, FAILED -> true
    }
}
