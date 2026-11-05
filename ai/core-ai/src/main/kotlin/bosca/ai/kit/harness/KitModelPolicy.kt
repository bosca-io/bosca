package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Administrator-approved choices and defaults for a deployment profile; discovery does not grant entitlement. */
@Serializable
data class KitModelPolicy(
    val defaults: KitModelSelection = KitModelSelection(),
    val allowedModels: List<String> = emptyList(),
    val allowedReasoning: List<String> = emptyList(),
    val agentDefaults: Map<String, KitModelSelection> = emptyMap(),
)
