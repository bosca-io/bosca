package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Status of the conversation event shown while a native turn is running or after it ends. */
@Serializable
enum class KitHarnessTurnStatus {
    STREAMING,
    COMPLETED,
    CANCELLED,
    INTERRUPTED,
    FAILED;

    /** Whether the event reports a final outcome. */
    val terminal: Boolean get() = this != STREAMING
}
