package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** INTERRUPTED preserves an unknown native outcome; available partial output remains separate. */
@Serializable
enum class KitDelegationStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT, INTERRUPTED;

    val terminal: Boolean get() = this !in setOf(QUEUED, RUNNING)
}
