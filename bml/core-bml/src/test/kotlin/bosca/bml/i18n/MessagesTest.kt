package bosca.bml.i18n

import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The localization resolver + ambient t(): fallback chains, placeholder
 * formatting, per-candidate-locale plural selection, and the never-throw missing-key policy.
 */
class MessagesTest {

    private val source = MessageSource.of(
        defaultLocale = Locale.forLanguageTag("en"),
        catalogs = mapOf(
            "en" to MessageCatalog(
                messages = mapOf(
                    "home.title" to "Welcome to Acme",
                    "greeting" to "Hello, {name}!",
                    "en.only" to "Only English has this",
                ),
                plurals = mapOf(
                    "cart.items" to mapOf(
                        PluralCategory.ONE to "You have one item",
                        PluralCategory.OTHER to "You have {count} items",
                    ),
                ),
            ),
            "es" to MessageCatalog(
                messages = mapOf("home.title" to "Bienvenido a Acme", "greeting" to "¡Hola, {name}!"),
            ),
            "es-419" to MessageCatalog(
                messages = mapOf("home.title" to "Bienvenido a Acme (LatAm)"),
            ),
            "pl" to MessageCatalog(
                plurals = mapOf(
                    "cart.items" to mapOf(
                        PluralCategory.ONE to "Masz {count} przedmiot",
                        PluralCategory.FEW to "Masz {count} przedmioty",
                        PluralCategory.MANY to "Masz {count} przedmiotów",
                    ),
                ),
            ),
        ),
    )

    @BeforeTest
    fun resetWarnOnce() = Messages.resetWarnings()

    private fun render(tag: String, block: suspend () -> String): String = runBlocking {
        val ctx = RenderContext(locale = Locale.forLanguageTag(tag), messages = source)
        withRenderContext(ctx) { block() }
    }

    // ── fallback chain ───────────────────────────────────────────────────────

    @Test
    fun `exact locale wins over its base language`() {
        assertEquals("Bienvenido a Acme (LatAm)", render("es-419") { t("home.title") })
    }

    @Test
    fun `a regional locale falls back to its base language`() {
        assertEquals("¡Hola, Ada!", render("es-419") { t("greeting", "name" to "Ada") })
    }

    @Test
    fun `everything falls back to the source language`() {
        assertEquals("Only English has this", render("es-419") { t("en.only") })
    }

    @Test
    fun `chain generalizes past two levels by progressive truncation`() {
        val chain = Messages.fallbackChain(Locale.forLanguageTag("zh-Hant-TW"), Locale.forLanguageTag("en"))
        assertEquals(listOf("zh-Hant-TW", "zh-Hant", "zh", "en"), chain.map { it.toLanguageTag() })
    }

    // ── missing keys ─────────────────────────────────────────────────────────

    @Test
    fun `a missing key renders the key itself`() {
        assertEquals("nav.missing", render("es") { t("nav.missing") })
        assertEquals("nav.missing", render("es") { t("nav.missing", 3) })
    }

    // ── placeholders ─────────────────────────────────────────────────────────

    @Test
    fun `placeholders substitute by name`() {
        assertEquals("Hello, Ada!", render("en") { t("greeting", "name" to "Ada") })
    }

    @Test
    fun `a missing argument leaves the placeholder visible`() {
        assertEquals("Hello, {name}!", render("en") { t("greeting") })
    }

    @Test
    fun `a null argument renders empty`() {
        assertEquals("Hello, !", render("en") { t("greeting", "name" to null) })
    }

    @Test
    fun `unknown placeholders and literal braces pass through`() {
        assertEquals(
            "Take {this} and {count}",
            Messages.format("Take {this} and {count}", mapOf("other" to "x")),
        )
        assertEquals("if (a) { b }", Messages.format("if (a) { b }", mapOf("a" to 1)))
    }

    // ── plurals ──────────────────────────────────────────────────────────────

    @Test
    fun `plural selection uses the locale's own categories`() {
        assertEquals("You have one item", render("en") { t("cart.items", 1) })
        assertEquals("You have 5 items", render("en") { t("cart.items", 5) })
        assertEquals("Masz 3 przedmioty", render("pl") { t("cart.items", 3) })
        assertEquals("Masz 5 przedmiotów", render("pl") { t("cart.items", 5) })
        assertEquals("Masz 1 przedmiot", render("pl") { t("cart.items", 1) })
    }

    @Test
    fun `plural fallback re-selects the category under the fallback locale's rules`() {
        // es has no cart.items: falls to en, whose rules give OTHER for 3 — never Polish FEW.
        assertEquals("You have 3 items", render("es") { t("cart.items", 3) })
        // …and en's ONE for exactly 1.
        assertEquals("You have one item", render("es") { t("cart.items", 1) })
    }

    @Test
    fun `a missing category form falls back to the locale's other form`() {
        val catalog = MessageCatalog(
            plurals = mapOf("k" to mapOf(PluralCategory.OTHER to "{count} things")),
        )
        assertEquals("1 things", runBlocking {
            Messages.resolvePlural(
                MessageSource.of(catalogs = mapOf("en" to catalog)),
                Locale.ENGLISH, "k", 1,
            )
        })
    }

    @Test
    fun `a plural call against a plain entry degrades to that entry`() {
        assertEquals("Welcome to Acme", render("en") { t("home.title", 7) })
    }

    @Test
    fun `count rides as an implicit argument alongside named ones`() {
        val catalog = MessageCatalog(
            plurals = mapOf(
                "inbox" to mapOf(PluralCategory.OTHER to "{name} has {count} messages"),
            ),
        )
        val s = MessageSource.of(catalogs = mapOf("en" to catalog))
        assertEquals("Ada has 4 messages", runBlocking {
            Messages.resolvePlural(s, Locale.ENGLISH, "inbox", 4, mapOf("name" to "Ada"))
        })
    }
}
