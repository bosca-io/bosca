package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Public settings summary; deployment details and credentials are never exposed here. */
@Serializable
data class KitHarnessSettings(
    val policyVersion: String,
    val effectiveProfile: String,
    val source: String,
    val canOverride: Boolean,
    val choices: List<String>,
    val requestedProfile: String? = null,
    val notice: String? = null,
    val mcpOptions: List<KitMcpOption> = emptyList(),
    val mcpServers: List<String> = emptyList(),
    val requestedMcpServers: List<String>? = null,
    val mcpNotices: List<String> = emptyList(),
    val capabilityVersion: String? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val modelSource: String = "HARNESS",
    val reasoningSource: String = "HARNESS",
    val requestedModel: String? = null,
    val requestedReasoning: String? = null,
    val models: List<KitModelOption> = emptyList(),
    val reasoningOptions: List<KitModelOption> = emptyList(),
)
