package bosca.recommendations.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A runtime instance of a [PersonalizationSignalDefinition] — a keyed personalization value computed from a
 * profile attribute by the definition's JSONata expression, cached (as a list) in `ProfileAttribute.signals`.
 * [key] is the definition's key (the feature / cohort membership name); [value] is the produced value,
 * interpreted downstream per the definition's [PersonalizationSignalValueType].
 */
@Serializable
data class PersonalizationSignal(
    val key: String,
    val value: JsonElement,
)
