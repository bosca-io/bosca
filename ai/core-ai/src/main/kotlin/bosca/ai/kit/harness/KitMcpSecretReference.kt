package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** A runtime environment reference; the value is never resolved by Runner or stored in settings. */
@Serializable
data class KitMcpSecretReference(val environment: String, val prefix: String = "")
