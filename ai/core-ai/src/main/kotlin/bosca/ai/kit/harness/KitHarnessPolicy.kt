package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Private `kit.harness` Configuration value; its group ACL controls overrides and administration. */
@Serializable
data class KitHarnessPolicy(
    val defaultProfile: String,
    val allowedProfiles: List<String>,
    val agentDefaults: Map<String, String> = emptyMap(),
    val models: Map<String, KitModelPolicy> = emptyMap(),
    val delegation: KitDelegationPolicy? = null,
    /** Administrator-approved `host[:port]` destinations the egress proxy allows for every profile. */
    val egressHosts: List<String> = emptyList(),
)
