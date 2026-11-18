package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Agent.configuration.harnessMcp entry; the registration must also be assigned through an Agent tool. */
@Serializable
data class KitMcpSelection(val key: String, val required: Boolean = true)
