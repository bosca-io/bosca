package bosca.experimentation.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One value in a feature flag's palette of candidate values.
 *
 * Variations are the source of truth for the values a flag can return. Targeting
 * rules and rollouts reference variations by [key] rather than embedding values
 * directly, so changing a value's representation only requires editing the
 * variation — every rule that serves it stays valid.
 *
 * The variation [key] is the stable identifier used by bucket assignment and by
 * experiment result aggregation. Renaming a key reshuffles bucket ranges, so
 * keys should be treated as forever-stable once a flag is in production.
 *
 * @property key stable identifier within the flag (e.g., "control", "treatment-a")
 * @property name human-readable label shown in dashboards
 * @property description optional explanation of what this variation represents
 * @property value the JSON value returned to clients when this variation is served
 */
@Serializable
data class Variation(
    val key: String,
    val name: String,
    val description: String = "",
    @Contextual
    val value: JsonElement
) {
    init {
        require(key.isNotBlank()) { "Variation key must not be blank" }
        require(name.isNotBlank()) { "Variation name must not be blank" }
    }
}
