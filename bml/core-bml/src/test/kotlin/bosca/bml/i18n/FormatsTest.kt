package bosca.bml.i18n

import bosca.bml.render.RenderContext
import bosca.bml.render.withRenderContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locale-aware formatting helpers. Assertions normalize the CLDR
 * whitespace variants (NBSP / narrow NBSP) so JDK data updates don't flake the suite.
 */
class FormatsTest {

    private fun render(tag: String, block: suspend () -> String): String = runBlocking {
        withRenderContext(RenderContext(locale = Locale.forLanguageTag(tag))) { block() }
    }.replace(' ', ' ').replace(' ', ' ')

    @Test
    fun `numbers group and separate per locale`() {
        assertEquals("1,234,567.89", render("en") { formatNumber(1_234_567.89) })
        assertEquals("1.234.567,89", render("es") { formatNumber(1_234_567.89) })
    }

    @Test
    fun `percent formats a fraction`() {
        assertEquals("42%", render("en") { formatPercent(0.42) }.replace(" ", ""))
    }

    @Test
    fun `currency follows the locale's placement and the currency's digits`() {
        assertEquals("$1,234.50", render("en-US") { formatCurrency(1_234.5, "USD") })
        val de = render("de") { formatCurrency(1_234.5, "EUR") }
        assertTrue("1.234,50" in de && "€" in de, "unexpected de currency: $de")
        // JPY has zero fraction digits — the currency wins over the locale default.
        // (1234.6, not a .5 tie: NumberFormat rounds HALF_EVEN.)
        assertEquals("¥1,235", render("en-US") { formatCurrency(1_234.6, "JPY") })
    }

    @Test
    fun `dates localize month names and order`() {
        val date = LocalDate.of(2026, 7, 4)
        assertEquals("Jul 4, 2026", render("en-US") { formatDate(date) })
        val es = render("es") { formatDate(date) }.lowercase()
        assertTrue("jul" in es && "2026" in es, "unexpected es date: $es")
    }

    @Test
    fun `instants format in an explicit zone`() {
        val instant = Instant.parse("2026-07-04T15:30:00Z")
        val chicago = ZoneId.of("America/Chicago")
        assertEquals("Jul 4, 2026", render("en-US") { formatDate(instant, chicago) })
        val dateTime = render("en-US") { formatDateTime(instant, chicago) }
        assertTrue("Jul 4, 2026" in dateTime && "10:30" in dateTime, "unexpected datetime: $dateTime")
    }
}
