package bosca.bml.sample

import bosca.bml.render.RenderContext
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives `scenario.bml` (generated in-memory) with different `{mode}` route params to exercise BOTH arms of
 * its generated branches: empty vs non-empty `for` loops, the `if/else-if/else` chain, defaulted-prop
 * fallbacks, and the scoped-style `useStyleOnce` dedup. Moves bml-sample's generated-code branch coverage.
 */
class ScenarioPageRenderTest {

    private fun render(mode: String): String = runBlocking {
        val ctx = RenderContext(params = mapOf("mode" to mode))
        bml.generated.ScenarioPage.render(ctx)
        ctx.toString()
    }

    @Test fun `empty mode takes the empty branch and runs no loop bodies`() {
        val h = render("empty")
        assertTrue("""<p class="state">none</p>""" in h, h)
        assertFalse("<li>" in h, "for-(i,x) must not iterate on an empty list:\n$h")
        assertFalse("""<span class="y">""" in h, "for-y must not iterate on an empty list:\n$h")
        assertTrue("Items (0)" in h, "item-list uses its default title and an empty list:\n$h")
    }

    @Test fun `single mode takes the else-if branch and the index loop runs once`() {
        val h = render("one")
        assertTrue("""<p class="state">single</p>""" in h, h)
        assertTrue("0=solo" in h, "for-(i,x) ran once with index 0:\n$h")
    }

    @Test fun `multi mode takes the else branch and both loops iterate`() {
        val h = render("full")
        assertTrue("""<p class="state">3 total</p>""" in h, h)
        assertTrue("0=a" in h && "1=b" in h && "2=c" in h, "indexed loop:\n$h")
        assertEquals(3, Regex("""class="y"""").findAll(h).count(), "for-y ran three times:\n$h")
        assertTrue("Items (3)" in h, h)
    }

    @Test fun `defaulted prop fallback - badge without tone uses info, with tone uses hot`() {
        val h = render("full")
        assertTrue("badge badge-info" in h, "tone defaulted to info:\n$h")
        assertTrue("badge badge-hot" in h, "tone provided as hot:\n$h")
    }

    @Test fun `each scoped component rendered twice inlines its style exactly once`() {
        // useStyleOnce: the first render of each scoped component inlines its <style>, the second is deduped
        val h = render("full")
        assertEquals(1, Regex("display: inline-block").findAll(h).count(), "badge style once")
        assertEquals(1, Regex("font-variant-numeric").findAll(h).count(), "counter-view style once")
        assertEquals(1, Regex("font-family: system-ui").findAll(h).count(), "item-list style once")
    }

    @Test fun `defaulted and provided values for counter-view and item-list both render`() {
        val h = render("full")
        assertTrue("Count: 0" in h, "counter-view default count 0:\n$h")
        assertTrue("Count: 3" in h, "counter-view provided count:\n$h")
        assertTrue("Items (3)" in h && "Second (3)" in h, "item-list default + provided title:\n$h")
    }

    @Test fun `raw interpolation is emitted unescaped`() {
        assertTrue("<i>full</i>" in render("full"))
    }

    @Test fun `bound, interpolated, and spread attributes all render`() {
        val h = render("full")
        assertTrue("""title="full"""" in h, "bound :title:\n$h")
        assertTrue("""class="m-full"""" in h, "interpolated class:\n$h")
        assertTrue("""data-n="3"""" in h, "spread attribute:\n$h")
    }

    @Test fun `a non-live island serializes its bound props`() {
        val h = render("full")
        assertTrue("""data-bml-island="size"""" in h, h)
        assertTrue("data-bml-props" in h && "start" in h && "3" in h, "island props serialized:\n$h")
    }
}
