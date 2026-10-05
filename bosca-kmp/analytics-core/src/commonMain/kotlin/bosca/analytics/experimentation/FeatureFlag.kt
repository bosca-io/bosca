package bosca.analytics.experimentation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Resolved value of one feature flag for the current identity and device. */
@Serializable
data class FeatureFlag(
    val flagKey: String,
    val value: JsonElement,
    val variationKey: String? = null,
    val experimentId: String? = null,
)
