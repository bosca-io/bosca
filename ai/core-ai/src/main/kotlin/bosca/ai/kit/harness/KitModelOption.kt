package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** One harness-advertised value, without native configuration keys or provider credentials. */
@Serializable
data class KitModelOption(
    val value: String,
    val name: String,
)
