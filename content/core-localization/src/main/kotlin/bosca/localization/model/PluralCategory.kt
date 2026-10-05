package bosca.localization.model

import kotlinx.serialization.Serializable

/**
 * CLDR plural categories used when storing one row per plural form.
 *
 * A given language uses a subset of these categories: English uses only [ONE] and [OTHER],
 * while languages such as Arabic use the full range. Consumers that need the complete set
 * of forms for a locale should consult CLDR plural rules at export time.
 *
 * Stored in the database as a lowercase `varchar` value (e.g. `"zero"`, `"other"`).
 */
@Serializable
enum class PluralCategory {
    ZERO,
    ONE,
    TWO,
    FEW,
    MANY,
    OTHER;

    /** Returns the lowercase wire representation used by CLDR, Crowdin, and the database. */
    fun cldrValue(): String = name.lowercase()

    companion object {
        /** Parses a CLDR plural category from its lowercase wire representation. */
        fun fromCldrValue(value: String): PluralCategory = valueOf(value.uppercase())
    }
}
