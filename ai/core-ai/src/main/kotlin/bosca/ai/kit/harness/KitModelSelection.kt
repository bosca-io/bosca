package bosca.ai.kit.harness

import kotlinx.serialization.Serializable

/** Normalized model and reasoning choices; null delegates that control to managed defaults. */
@Serializable
data class KitModelSelection(
    val model: String? = null,
    val reasoning: String? = null,
)
