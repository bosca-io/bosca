package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** State reported by the runtime supervisor over its control protocol. */
@Serializable
enum class KitRuntimePhase {
    PREPARED,
    RESTORED,
    READY,
    DRAINING,
    ARCHIVED,
    FAILED,
}
