package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Lifecycle of a managed Kit session. RESUMING describes startup with a saved archive in the API. */
@Serializable
enum class KitRuntimeSessionState {
    STOPPED,
    STARTING,
    READY,
    BUSY,
    WAITING_FOR_APPROVAL,
    STOPPING,
    FAILED,
    RESUMING,
}
