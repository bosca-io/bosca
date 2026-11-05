package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Generation-local connection readiness, with a sanitized recovery message. */
@Serializable
data class KitMcpStatus(val key: String, val ready: Boolean, val message: String? = null)
