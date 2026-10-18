package bosca.bml.library

import kotlinx.serialization.Serializable

/** An authored localization declaration owned by one compiled component's source file. */
@Serializable
data class BmlLibraryMessage(
    val key: String,
    val message: String?,
    val pluralForms: Map<String, String> = emptyMap(),
    /** Placeholder names and their authored expressions, for translator context. */
    val placeholders: Map<String, String> = emptyMap(),
    val origin: String,
    val line: Int,
) {
    /** Validates a declaration before its source defaults enter a consuming catalog. */
    fun validate() {
        require(key.isNotBlank() && key.none(Char::isISOControl)) { "Invalid localization key '$key'" }
        require(origin in setOf("ELEMENT", "ATTRIBUTE", "FUNCTION") && line > 0) {
            "Invalid localization origin for '$key'"
        }
        require(message == null || pluralForms.isEmpty()) { "Localization key '$key' has both plain and plural defaults" }
        require(message != null || pluralForms.isNotEmpty() || origin == "FUNCTION") {
            "Localization key '$key' has no authored default"
        }
        require(pluralForms.isEmpty() || "OTHER" in pluralForms &&
            pluralForms.keys.all { it in setOf("ZERO", "ONE", "TWO", "FEW", "MANY", "OTHER") }) {
            "Invalid localization plural forms for '$key'"
        }
        require(placeholders.keys.all { it.isNotBlank() && it.none(Char::isISOControl) }) {
            "Invalid localization placeholders for '$key'"
        }
    }
}
