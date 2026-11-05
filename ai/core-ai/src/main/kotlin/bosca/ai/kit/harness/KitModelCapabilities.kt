package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Session-advertised controls; the revision excludes mutable current values. */
@Serializable
data class KitModelCapabilities(
    val version: String,
    val models: List<KitModelOption> = emptyList(),
    val reasoning: List<KitModelOption> = emptyList(),
    val effective: KitModelSelection = KitModelSelection(),
)
