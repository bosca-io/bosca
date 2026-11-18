package bosca.ai.kit.harness

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Immutable effective settings recorded before submitting a turn to the native harness. */
@Serializable
data class KitHarnessResolution(
    /** Optional private policy override; null uses the deployment's managed defaults. */
    @Contextual val configurationId: UUID? = null,
    val policyVersion: String,
    val source: String,
    val configuration: KitHarnessConfiguration,
    val authority: KitRuntimeAuthority,
    val notice: String? = null,
    val requested: KitHarnessChoice? = null,
    val mcpConnections: List<KitMcpConnection> = emptyList(),
    val mcpOptions: List<KitMcpOption> = emptyList(),
    val mcpNotices: List<String> = emptyList(),
    val modelPolicy: KitModelPolicy? = null,
    val modelSettings: KitModelSelection = KitModelSelection(),
    val modelSource: String = "HARNESS",
    val reasoningSource: String = "HARNESS",
    val modelCapabilities: KitModelCapabilities? = null,
)
