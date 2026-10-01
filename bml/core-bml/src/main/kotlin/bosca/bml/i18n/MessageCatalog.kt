package bosca.bml.i18n

import bosca.bml.render.BmlLocales
import java.util.Locale

/**
 * CLDR plural category, mirroring the localization domain's `PluralCategory` enum —
 * one authored/translated form per category per language. Which category a count selects is a
 * per-language question answered by [PluralRules].
 */
enum class PluralCategory { ZERO, ONE, TWO, FEW, MANY, OTHER }

/**
 * An immutable snapshot of one locale's strings: plain [messages] by key, and
 * [plurals] as per-category forms by key. Catalogs are per-EXACT-locale — fallback across
 * locales (`es-419` -> `es` -> source language) is the resolver's job ([Messages]), not merged
 * into the snapshot, so one catalog never hides which locale actually supplied a string.
 */
class MessageCatalog(
    val messages: Map<String, String> = emptyMap(),
    val plurals: Map<String, Map<PluralCategory, String>> = emptyMap(),
) {
    /** The plain message for [key], or null when this locale doesn't carry it. */
    fun message(key: String): String? = messages[key]

    /**
     * The plural form for [key] under [category], falling back to this locale's OTHER form —
     * a language whose translation omits a category still renders its most general form.
     * Null when this locale has no plural entry for the key at all.
     */
    fun plural(key: String, category: PluralCategory): String? =
        plurals[key]?.let { it[category] ?: it[PluralCategory.OTHER] }

    val isEmpty: Boolean get() = messages.isEmpty() && plurals.isEmpty()

    companion object {
        val EMPTY: MessageCatalog = MessageCatalog()
    }
}

/**
 * Supplies per-locale [MessageCatalog]s. Implementations own fetching and caching
 * (the GraphQL-backed source loads the site's bound localization project; [of] pins fixed
 * catalogs for tests and hand-rolled hosts); the [Messages] resolver owns fallback and
 * formatting. A source must be total: unknown locales return [MessageCatalog.EMPTY], never
 * throw — rendering degrades to authored fallbacks/keys, it does not fail.
 */
interface MessageSource {

    /**
     * The terminal fallback of every lookup chain — the localization project's source language.
     * Lookups that miss the request locale and its truncations end here before giving up.
     */
    val defaultLocale: Locale get() = BmlLocales.DEFAULT

    /** The catalog for one exact locale. Total: unknown locale -> [MessageCatalog.EMPTY]. */
    suspend fun catalog(locale: Locale): MessageCatalog

    companion object {
        /** No catalogs at all — every lookup falls through to authored fallbacks/keys. */
        val Empty: MessageSource = object : MessageSource {
            override suspend fun catalog(locale: Locale): MessageCatalog = MessageCatalog.EMPTY
        }

        /** A fixed in-memory source keyed by BCP-47 tag (case-insensitive) — tests, custom hosts. */
        fun of(
            defaultLocale: Locale = BmlLocales.DEFAULT,
            catalogs: Map<String, MessageCatalog>,
        ): MessageSource = object : MessageSource {
            private val byTag = catalogs.mapKeys { (tag, _) -> tag.lowercase() }
            override val defaultLocale: Locale = defaultLocale
            override suspend fun catalog(locale: Locale): MessageCatalog =
                byTag[locale.toLanguageTag().lowercase()] ?: MessageCatalog.EMPTY
        }
    }
}
