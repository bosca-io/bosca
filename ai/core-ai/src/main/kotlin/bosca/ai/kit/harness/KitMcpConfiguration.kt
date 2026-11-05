package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Typed `harness` section of an existing MCP registration's configuration. */
@Serializable
data class KitMcpConfiguration(
    val url: String? = null,
    val executable: String? = null,
    val environment: Map<String, KitMcpSecretReference> = emptyMap(),
    val headers: Map<String, KitMcpSecretReference> = emptyMap(),
)
