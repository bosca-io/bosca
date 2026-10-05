package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiTargetRenderTest {

    // ── plain text ──────────────────────────────────────────────────────────

    @Test
    fun `plain text strips tags, bullets lists, decodes entities`() {
        val text = PlainTextRenderer.render(
            "<h1>Title</h1><p>Hello &amp; bye</p><ul><li>a</li><li>b</li></ul>",
        )
        assertTrue(text.contains("Title"), text)
        assertTrue(text.contains("Hello & bye"), text)
        assertTrue(text.contains("- a") && text.contains("- b"), text)
        assertFalse(text.contains("<"), text)
    }

    @Test
    fun `plain text drops script and style content`() {
        val text = PlainTextRenderer.render(
            "<style>.a{color:red}</style><p>keep</p><script>alert(1)</script>",
        )
        assertTrue(text.contains("keep"), text)
        assertFalse(text.contains("alert"), text)
        assertFalse(text.contains("color:red"), text)
    }

    @Test
    fun `plain text drops head content but keeps the body`() {
        val text = PlainTextRenderer.render(
            "<!doctype html><html><head><title>Doc Title</title><meta name=\"x\"></head>" +
                "<body><h1>Hello</h1></body></html>",
        )
        assertTrue(text.contains("Hello"), text)
        assertFalse(text.contains("Doc Title"), "head content is not body text: $text")
    }

    // ── email ─────────────────────────────────────────────────────────────────

    @Test
    fun `email strips scripts`() {
        val html = EmailRenderer.render("<div>x</div><script>alert(1)</script>")
        assertFalse(html.contains("alert"), html)
        assertTrue(html.contains("<div>x</div>"), html)
    }

    @Test
    fun `email inlines class and tag selectors`() {
        val html = EmailRenderer.render(
            """<h1>t</h1><div class="card">x</div>""",
            "h1 { font-size: 20px; } .card { color: red; }",
        )
        assertTrue(html.contains("""<h1 style="font-size: 20px">"""), html)
        assertTrue(html.contains("""style="color: red""""), html)
    }

    @Test
    fun `email keeps existing inline style winning on conflict`() {
        val html = EmailRenderer.render(
            """<p class="m" style="color: blue">x</p>""",
            ".m { color: red; margin: 0; }",
        )
        assertTrue(html.contains("color: blue"), html)
        assertTrue(html.contains("margin: 0"), html)
        assertFalse(html.contains("color: red"), html)
    }

    @Test
    fun `email inlines id selectors`() {
        val html = EmailRenderer.render("""<main id="x">y</main>""", "#x { padding: 8px; }")
        assertTrue(html.contains("""style="padding: 8px""""), html)
    }
}
