package bosca.design.example

import bosca.bml.render.RenderContext
import bosca.design.generated.TimeComponent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimeComponentTest {
    private fun render(value: String, vararg props: Pair<String, Any>): String = runBlocking {
        val context = RenderContext()
        TimeComponent.render(context, mapOf("value" to value, *props)) {}
        context.toString()
    }

    @Test
    fun `default converts to UTC and custom formats retain source offset in English`() {
        val value = "2026-10-18T08:15:00-05:00"
        assertTrue(">Oct 18, 13:15 UTC</time>" in render(value))
        assertTrue(">Sunday, October 18 08:15 -05:00</time>" in render(value, "format" to "EEEE, MMMM d HH:mm XXX"))
        assertTrue(">2026</time>" in render(value, "format" to "yyyy", "relative" to true))
    }

    @Test
    fun `relative units suffix future and old-date fallback retain the source contract`() {
        fun relative(seconds: Long, ago: Boolean = false) = render(Instant.now().minusSeconds(seconds).toString(), "relative" to true, "ago" to ago)
        assertTrue(">just now</time>" in relative(10))
        assertTrue(">just now</time>" in relative(-10))
        assertTrue(">5m</time>" in relative(310))
        assertTrue(">2h ago</time>" in relative(7210, true))
        assertTrue(">in 2h</time>" in relative(-7210, true))
        assertTrue(">2d</time>" in relative(172810))
        val old = Instant.now().minusSeconds(700000).atOffset(ZoneOffset.UTC)
        val label = old.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
        assertTrue(">$label</time>" in render(old.toString(), "relative" to true, "ago" to true))
    }

    @Test
    fun `invalid values remain escaped literal text and invalid patterns fail loudly`() {
        assertTrue(">bad &lt;value&gt;</time>" in render("bad <value>"))
        assertFailsWith<IllegalArgumentException> { render("2026-10-18T08:15:00Z", "format" to "invalid") }
    }
}
