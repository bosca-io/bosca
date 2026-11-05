package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Manager-observed running task and cleanup state; no Docker credentials enter this response. */
@Serializable
data class KitSwarmExecution(val created: String, val terminal: Boolean, val endpoint: String?,
    val image: String?, val imageId: String?, val taskId: String? = null)
