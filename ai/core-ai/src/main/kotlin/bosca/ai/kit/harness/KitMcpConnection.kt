package bosca.ai.kit.harness

import bosca.ai.agents.model.McpTransportType
import kotlinx.serialization.Serializable

/** Private immutable connection plan containing approved commands and secret references only. */
@Serializable
data class KitMcpConnection(
    val key: String,
    val name: String,
    val transport: McpTransportType,
    val required: Boolean,
    val url: String? = null,
    val command: List<String> = emptyList(),
    val environment: Map<String, KitMcpSecretReference> = emptyMap(),
    val headers: Map<String, KitMcpSecretReference> = emptyMap(),
)
