package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeStatus(
    val sessionId: String,
    val generation: Long,
    val phase: String,
    /** Fresh identity for this supervisor process; never restored from a checkpoint. */
    val instanceId: String,
    val nativeSessionId: String? = null,
    val activeTurn: String? = null,
    val pendingPermissions: Int = 0,
    val activeTools: Int = 0,
    val epoch: Long = 0,
    val retireRequested: Boolean = false,
    val failure: String? = null,
    val versions: RuntimeVersions? = null,
)
