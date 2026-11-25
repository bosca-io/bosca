package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Safe conversation choice without registry configuration or credentials. */
@Serializable
data class KitMcpOption(val key: String, val name: String, val required: Boolean)
