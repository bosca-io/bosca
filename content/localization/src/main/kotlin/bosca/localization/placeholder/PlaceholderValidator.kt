package bosca.localization.placeholder

import bosca.localization.model.LocalizationPlaceholder

/**
 * Verifies that a translation preserves every placeholder declared on the source string.
 *
 * The localization service runs validation before accepting a translation so mismatches
 * surface at write time rather than later, when broken exports hit production consumers.
 */
object PlaceholderValidator {

    /**
     * Describes a placeholder discrepancy between the declared set and what the translation
     * contains.
     *
     * @property missing declared placeholders absent from the translation (blocking)
     * @property extra placeholders present in the translation that are not declared
     *  (non-blocking warning: translators sometimes legitimately interpolate context)
     */
    data class Result(val missing: Set<String>, val extra: Set<String>) {
        /** True iff every declared placeholder is present in the translation. */
        val isValid: Boolean get() = missing.isEmpty()
    }

    /**
     * Compares the [declared] placeholder list to the ICU placeholders present in [text]
     * and returns the discrepancies.
     */
    fun validate(declared: List<LocalizationPlaceholder>, text: String): Result {
        val declaredNames = declared.mapTo(mutableSetOf()) { it.name }
        val found = PlaceholderExtractor.names(text)
        return Result(
            missing = declaredNames - found,
            extra = found - declaredNames
        )
    }
}
