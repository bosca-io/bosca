package bosca.bml.render

import java.util.Locale

/**
 * Cookie a site's client JS stores the visitor's chosen locale in. Client-managed
 * like [BOSCA_TOKEN_COOKIE]: the server only reads it during per-request locale negotiation
 * (an explicit `?lang` wins, `Accept-Language` is the fallback); a locale-switcher UI is site
 * code, not framework code.
 */
public const val BML_LOCALE_COOKIE: String = "bml_locale"

/**
 * A site's supported-locale policy and the negotiation over it. Pure — no HTTP
 * types — so the SSR server, the message server, and tests share one implementation.
 *
 * Negotiation is real RFC 4647 over `java.util.Locale`'s implementation, not a split-on-comma
 * approximation: [Locale.LanguageRange.parse] handles the full `Accept-Language` grammar
 * (q-weights, wildcards, case-insensitivity), [Locale.lookupTag] runs the Lookup scheme
 * (progressive subtag truncation, `zh-Hant-TW` -> `zh-Hant` -> `zh`), and [Locale.filterTags]
 * covers the inverse Basic-Filtering direction Lookup can't (a broad `es` request served by an
 * `es-419`-only site). Zero-weight ranges (`en;q=0` = "not acceptable") remain in the match set
 * so a broader wildcard cannot select a locale the client explicitly excluded.
 *
 * [supportedTags] lists BCP-47 tags in priority order; the first entry is the site [default].
 * Blank entries are dropped; an empty (or all-blank) list collapses to a single-locale
 * [DEFAULT] site, which keeps negotiation inert for sites that configure nothing.
 */
class BmlLocales(supportedTags: List<String> = emptyList()) {

    /** The configured locales in priority order (first = default); never empty. */
    val supported: List<Locale> = supportedTags
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Locale.forLanguageTag(it) }
        .filter { it.toLanguageTag() != "und" } // forLanguageTag never throws; garbage parses to "und"
        .ifEmpty { listOf(DEFAULT) }

    /** The site's default locale — the first configured entry. */
    val default: Locale = supported.first()

    // The lookup/filter tag universe, plus a case-insensitive way back to the Locale —
    // BCP-47 tags are case-insensitive and the JDK matcher lowercases what it returns.
    private val tags: List<String> = supported.map { it.toLanguageTag() }
    private val byLowercaseTag: Map<String, Locale> = supported.associateBy { it.toLanguageTag().lowercase() }

    /**
     * Negotiates the request locale, first match wins: the explicit [lang] override (`?lang`),
     * the site-managed [cookie] ([BML_LOCALE_COOKIE]), then the raw [acceptLanguage] header.
     * Anything unmatched falls through to the [default] — a page always renders in a supported
     * locale, never 406s.
     */
    fun resolve(lang: String? = null, cookie: String? = null, acceptLanguage: String? = null): Locale {
        lang?.let { candidate -> match(candidate)?.let { return it } }
        cookie?.let { candidate -> match(candidate)?.let { return it } }
        acceptLanguage?.let { header -> negotiate(header)?.let { return it } }
        return default
    }

    /**
     * Matches one candidate tag (a `?lang` value, a cookie value) against the supported list —
     * the same Lookup + Basic-Filtering pair as header negotiation, for one unweighted range.
     * Null when unsupported or unparseable; a bare `*` expresses no preference and matches nothing.
     */
    fun match(candidate: String): Locale? {
        val tag = candidate.trim()
        if (tag.isEmpty() || tag == "*") return null
        val range = try {
            listOf(Locale.LanguageRange(tag))
        } catch (_: IllegalArgumentException) {
            return null // not a well-formed language range (garbage input, not an error)
        }
        return bestFor(range)
    }

    /**
     * Full `Accept-Language` negotiation (RFC 9110 §12.5.4 over RFC 4647). Null when the header
     * is unparseable, empty, or matches nothing — the caller falls through to the next source.
     */
    fun negotiate(acceptLanguage: String): Locale? {
        val ranges = try {
            Locale.LanguageRange.parse(acceptLanguage)
        } catch (_: IllegalArgumentException) {
            return null // malformed header: ignore it rather than failing the request
        }
        return bestFor(ranges)
    }

    // Per range, in weight order (parse() returns them weight-sorted, stably): Lookup first (the
    // one-tag-for-a-request scheme — exact match + progressive truncation), then Basic Filtering
    // for what Lookup can't serve — a range broader than every tag (`es` vs an `es-419`-only
    // site) and the `*` wildcard (Lookup ignores it; filtering resolves it to our tag order,
    // i.e. the default). Ranges are tried ONE AT A TIME so a strongly-preferred broad range
    // (`pt;q=0.9`) beats a weakly-tolerated exact one (`en;q=0.1`) — whole-list Lookup would
    // let the exact match win on the weaker preference.
    private fun bestFor(ranges: List<Locale.LanguageRange>): Locale? {
        for (range in ranges.filter { it.weight > 0.0 }) {
            val alone = listOf(range)
            val acceptableTags = tags.filter { tag -> isAcceptable(tag, ranges) }
            val matched = Locale.lookupTag(alone, acceptableTags)
                ?: Locale.filterTags(alone, acceptableTags).firstOrNull()
            matched?.let { return byLowercaseTag[it.lowercase()] }
        }
        return null
    }

    /**
     * RFC quality is determined by the most specific range matching a representation. This keeps
     * `*;q=1, en;q=0` from selecting English while still allowing `en-US;q=1, en;q=0` to select
     * the explicitly permitted regional variant.
     */
    private fun isAcceptable(tag: String, ranges: List<Locale.LanguageRange>): Boolean {
        val mostSpecific = ranges
            .filter { range ->
                Locale.filterTags(listOf(Locale.LanguageRange(range.range)), listOf(tag)).isNotEmpty()
            }
            .maxByOrNull { range ->
                if (range.range == "*") 0 else range.range.split('-').size
            }
        return mostSpecific?.weight?.let { it > 0.0 } ?: true
    }

    companion object {
        /** The locale of a site that configures none — and [RenderContext]'s default. */
        val DEFAULT: Locale = Locale.ENGLISH
    }
}
