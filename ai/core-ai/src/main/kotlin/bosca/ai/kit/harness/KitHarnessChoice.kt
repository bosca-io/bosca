package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** A constrained choice against the policy revision shown to the caller. */
@Serializable
data class KitHarnessChoice(
    val profile: String,
    val policyVersion: String,
    val mcpServers: List<String>? = null,
    val model: String? = null,
    val reasoning: String? = null,
    val capabilityVersion: String? = null,
)
