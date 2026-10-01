package bosca.localization.model

import kotlinx.serialization.Serializable

/**
 * A single placeholder declared on a [LocalizationString], used both for validation
 * (translations must preserve every declared placeholder) and for surfacing context
 * to translators in the admin UI.
 *
 * Placeholders follow ICU MessageFormat conventions. Exporters convert them to the
 * target platform's placeholder syntax at export time.
 *
 * @property name the placeholder identifier, e.g. `"count"` in ICU `{count, number}`
 * @property type a hint describing the value type (`string`, `number`, `date`, `time`, `currency`)
 * @property example an optional sample value translators can plug in mentally while translating
 */
@Serializable
data class LocalizationPlaceholder(
    val name: String,
    val type: String = "string",
    val example: String? = null
)
