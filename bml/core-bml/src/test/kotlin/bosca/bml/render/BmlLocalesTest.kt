package bosca.bml.render

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The locale negotiation policy: source priority (`?lang` -> cookie ->
 * `Accept-Language`), full RFC 4647 header semantics (q-weights, q=0 exclusion, wildcard,
 * lookup truncation, basic-filter broadening), and the inert single-locale default.
 */
class BmlLocalesTest {

    private val locales = BmlLocales(listOf("en", "es", "es-419", "pt-BR"))

    private fun tag(locale: Locale): String = locale.toLanguageTag()

    // ── configuration ────────────────────────────────────────────────────────

    @Test
    fun `no configuration collapses to a single-locale en site`() {
        val inert = BmlLocales()
        assertEquals("en", tag(inert.default))
        assertEquals(listOf("en"), inert.supported.map(::tag))
        assertEquals("en", tag(inert.resolve(lang = "es", acceptLanguage = "es, fr;q=0.9")))
    }

    @Test
    fun `blank and garbage entries are dropped, first survivor is the default`() {
        val cleaned = BmlLocales(listOf(" ", "!!!!", "es-419", "en"))
        assertEquals("es-419", tag(cleaned.default))
        assertEquals(listOf("es-419", "en"), cleaned.supported.map(::tag))
    }

    // ── source priority ──────────────────────────────────────────────────────

    @Test
    fun `lang override wins over cookie and header`() {
        val resolved = locales.resolve(lang = "es", cookie = "pt-BR", acceptLanguage = "en")
        assertEquals("es", tag(resolved))
    }

    @Test
    fun `cookie wins over header when there is no override`() {
        assertEquals("pt-BR", tag(locales.resolve(cookie = "pt-BR", acceptLanguage = "en")))
    }

    @Test
    fun `an unsupported override falls through to the cookie, not straight to default`() {
        assertEquals("es-419", tag(locales.resolve(lang = "de", cookie = "es-419")))
    }

    @Test
    fun `nothing resolvable falls back to the site default`() {
        assertEquals("en", tag(locales.resolve(lang = "de", cookie = "fr", acceptLanguage = "ja, zh;q=0.9")))
        assertEquals("en", tag(locales.resolve()))
    }

    // ── single-candidate matching (lang/cookie) ─────────────────────────────

    @Test
    fun `match is exact first, case-insensitively, returning the configured casing`() {
        assertEquals("es-419", tag(locales.match("ES-419") ?: error("no match")))
        assertEquals("pt-BR", tag(locales.match("pt-br") ?: error("no match")))
    }

    @Test
    fun `match truncates a regional candidate to its supported base language`() {
        assertEquals("es", tag(locales.match("es-MX") ?: error("no match")))
        assertEquals("en", tag(locales.match("en-GB") ?: error("no match")))
    }

    @Test
    fun `a broad candidate is served by the only regional variant of its language`() {
        val ptOnly = BmlLocales(listOf("en", "pt-BR"))
        assertEquals("pt-BR", tag(ptOnly.match("pt") ?: error("no match")))
    }

    @Test
    fun `unsupported, wildcard, blank, and malformed candidates match nothing`() {
        assertNull(locales.match("de"))
        assertNull(locales.match("*"))
        assertNull(locales.match("  "))
        assertNull(locales.match("not a tag!"))
    }

    // ── Accept-Language: full-spec behaviors ────────────────────────────────

    @Test
    fun `quality weights order the header, not its textual order`() {
        assertEquals("es", tag(locales.resolve(acceptLanguage = "en;q=0.7, es;q=0.9")))
    }

    @Test
    fun `equal weights keep header order`() {
        assertEquals("pt-BR", tag(locales.resolve(acceptLanguage = "pt-BR, es")))
    }

    @Test
    fun `q=0 is an exclusion, not a low preference`() {
        // en is explicitly not acceptable; es must win even though en is the site default.
        assertEquals("es", tag(locales.resolve(acceptLanguage = "en;q=0, es;q=0.1")))
    }

    @Test
    fun `a header of only exclusions resolves nothing and falls to default`() {
        assertEquals("en", tag(locales.resolve(acceptLanguage = "es;q=0")))
    }

    @Test
    fun `lookup truncates subtags progressively`() {
        assertEquals("es", tag(locales.resolve(acceptLanguage = "es-MX;q=0.9, en;q=0.5")))
    }

    @Test
    fun `a broad range is served by its regional variant when no exact tag exists`() {
        val ptOnly = BmlLocales(listOf("en", "pt-BR"))
        assertEquals("pt-BR", tag(ptOnly.resolve(acceptLanguage = "pt;q=0.9, en;q=0.1")))
    }

    @Test
    fun `wildcard alone resolves to the site default`() {
        assertEquals("en", tag(locales.resolve(acceptLanguage = "*")))
        val esFirst = BmlLocales(listOf("es-419", "en"))
        assertEquals("es-419", tag(esFirst.resolve(acceptLanguage = "*")))
    }

    @Test
    fun `wildcard defers to any concrete supported preference`() {
        assertEquals("es", tag(locales.resolve(acceptLanguage = "es;q=0.9, *;q=0.5")))
    }

    @Test
    fun `wildcard cannot select a locale excluded by a more specific range`() {
        assertEquals("es", tag(locales.resolve(acceptLanguage = "*;q=1, en;q=0")))
    }

    @Test
    fun `specific positive range can override a broader exclusion`() {
        val regional = BmlLocales(listOf("en", "en-US", "es"))
        assertEquals("en-US", tag(regional.resolve(acceptLanguage = "en-US;q=1, en;q=0")))
    }

    @Test
    fun `three-decimal weights and whitespace parse per the spec`() {
        assertEquals("es-419", tag(locales.resolve(acceptLanguage = " es-419 ; q=0.812 , en ; q=0.811 ")))
    }

    @Test
    fun `a malformed header is ignored rather than failing the request`() {
        assertEquals("en", tag(locales.resolve(acceptLanguage = ";;;===garbage")))
        // ...and does not block earlier sources from having already matched.
        assertEquals("es", tag(locales.resolve(cookie = "es", acceptLanguage = ";;;===garbage")))
    }
}
