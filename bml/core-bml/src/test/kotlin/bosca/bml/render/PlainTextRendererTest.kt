package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlainTextRendererTest {

    @Test
    fun `paragraphs and headings become readable blocks`() {
        val html = "<h1>Account ready</h1><p>Hello <strong>Ada</strong>.</p><p>Welcome aboard.</p>"

        assertEquals(
            "Account ready\n\nHello Ada.\n\nWelcome aboard.",
            PlainTextRenderer.render(html),
        )
    }

    @Test
    fun `br becomes a newline and whitespace collapses`() {
        assertEquals("Hello Ada\nWelcome back", PlainTextRenderer.render("  Hello   Ada<br> Welcome\nback  "))
    }

    @Test
    fun `links include useful destinations`() {
        val html = """
            <p>Open your <a href="https://example.com/account?a=1&amp;b=2">account</a>.</p>
            <p><a href="https://example.com/help">https://example.com/help</a></p>
            <p><a href="mailto:support@example.com">Email support</a></p>
        """.trimIndent()

        assertEquals(
            """
                Open your account (https://example.com/account?a=1&b=2).

                https://example.com/help

                Email support (support@example.com)
            """.trimIndent(),
            PlainTextRenderer.render(html),
        )
    }

    @Test
    fun `empty links retain their destination and unsafe links do not`() {
        val html = """
            <a href="https://example.com"></a>
            <a href="#">Placeholder</a>
            <a href="#section">Section placeholder</a>
            <a href="javascript:alert(1)">Do not copy</a>
        """.trimIndent()

        assertEquals(
            "https://example.com Placeholder Section placeholder Do not copy",
            PlainTextRenderer.render(html),
        )
    }

    @Test
    fun `unordered ordered and nested lists retain their markers`() {
        val html = """
            <ol start="3">
              <li>Third</li>
              <li value="7">Seventh
                <ul><li>Nested</li></ul>
              </li>
            </ol>
        """.trimIndent()

        assertEquals(
            """
                3. Third
                7. Seventh
                  - Nested
            """.trimIndent(),
            PlainTextRenderer.render(html),
        )
    }

    @Test
    fun `table cells are separated while layout rows remain readable`() {
        val html = """
            <table>
              <tr><th>Device</th><td>MacBook Pro</td></tr>
              <tr><th>Location</th><td>Atlanta, GA</td></tr>
            </table>
        """.trimIndent()

        assertEquals("Device | MacBook Pro\nLocation | Atlanta, GA", PlainTextRenderer.render(html))
    }

    @Test
    fun `image alternative text is retained and decorative images are ignored`() {
        assertEquals(
            "[Security diagram] Continue",
            PlainTextRenderer.render("""<img src="diagram.png" alt="Security diagram"><img src="rule.png" alt="">Continue"""),
        )
        assertEquals(
            "Bosca",
            PlainTextRenderer.render("""<img src="mark.png" alt="Bosca"> <span>Bosca</span>"""),
        )
    }

    @Test
    fun `head scripts styles comments and hidden content are dropped`() {
        val html = """
            <!doctype html>
            <html>
              <head><title>Document title</title><style>.x { color: red }</style></head>
              <body>
                <!-- internal note -->
                <div style="display: none">Preview duplicate</div>
                <div aria-hidden="true"><span>Icon label</span></div>
                <p>Visible</p>
                <script>if (a < b) alert("no")</script>
              </body>
            </html>
        """.trimIndent()

        val rendered = PlainTextRenderer.render(html)
        assertEquals("Visible", rendered)
        assertFalse(rendered.contains("Document title"))
        assertFalse(rendered.contains("Preview duplicate"))
        assertFalse(rendered.contains("Icon label"))
    }

    @Test
    fun `quoted greater-than signs do not terminate a tag`() {
        assertEquals(
            "comparison (https://example.com/?q=1>0)",
            PlainTextRenderer.render("""<a href="https://example.com/?q=1>0">comparison</a>"""),
        )
    }

    @Test
    fun `unquoted link destinations may contain slashes`() {
        assertEquals(
            "account (https://example.com/users/ada)",
            PlainTextRenderer.render("""<a href=https://example.com/users/ada>account</a>"""),
        )
    }

    @Test
    fun `horizontal rules and blockquotes remain visible`() {
        assertEquals(
            "Before\n\n---\n\n> Quoted\n\nAfter",
            PlainTextRenderer.render("<p>Before</p><hr><blockquote>Quoted</blockquote><p>After</p>"),
        )
    }

    @Test
    fun `named and unicode numeric entities decode`() {
        assertEquals(
            "&<>\"' — 😀",
            PlainTextRenderer.render("&amp;&lt;&gt;&quot;&apos; &mdash; &#x1F600;"),
        )
    }

    @Test
    fun `unknown entities remain and invalid numeric entities use the parser replacement`() {
        assertEquals(
            "&unknown; �",
            PlainTextRenderer.render("&unknown; &#999999999999; &#0;"),
        )
    }

    @Test
    fun `unterminated markup stops parsing cleanly`() {
        assertEquals("Before", PlainTextRenderer.render("Before<b"))
        assertEquals("Before", PlainTextRenderer.render("Before<script>alert(1)"))
    }

    @Test
    fun `plain text passes through with normalized whitespace`() {
        assertEquals("plain text", PlainTextRenderer.render(" plain   text "))
    }
}
