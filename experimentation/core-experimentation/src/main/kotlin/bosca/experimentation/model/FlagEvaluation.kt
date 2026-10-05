package bosca.experimentation.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The resolved value of a feature flag for a specific user/device context.
 *
 * Returned by the flag evaluation API and consumed by client SDKs. The [value]
 * is always the value of the variation that was served. The [variationKey]
 * names which variation that was. If the served variation came from a rule
 * with an attached experiment, [experimentId] identifies the experiment so
 * exposure events can be recorded against it.
 */
@Serializable
data class FlagEvaluation(
    val flagKey: String,
    val variationKey: String,
    @Contextual
    val value: JsonElement,
    @Contextual
    val experimentId: UUID? = null,
    /**
     * True when the evaluation used a fallback because targeting data or the
     * feature-flag service was unavailable. When [variationKey] is non-blank,
     * [value] is the flag's declared default. A blank key and JSON null [value]
     * tell consumers to use their own authored fallback.
     */
    val degraded: Boolean = false,
)
