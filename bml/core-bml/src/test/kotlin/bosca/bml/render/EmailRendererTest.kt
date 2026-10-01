package bosca.bml.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Branch-level coverage for [EmailRenderer] — css parsing, selector matching, and inlining edge cases. */
class EmailRendererTest {

    @Test
    fun `no css returns the html unchanged (scripts still stripped)`() {
        assertEquals("<p>hi</p>", EmailRenderer.render("<p>hi</p><script>x()</script>"))
    }

    @Test
    fun `tag-dot-class selector inlines`() {
        val out = EmailRenderer.render("""<p class="c">x</p>""", "p.c { color: red }")
        assertTrue("""style="color: red"""" in out, out)
    }

    @Test
    fun `multiple classes selector requires all classes present`() {
        val css = ".a.b { color: red }"
        assertTrue("style=" in EmailRenderer.render("""<p class="a b">x</p>""", css))
        assertFalse("style=" in EmailRenderer.render("""<p class="a">x</p>""", css))
    }

    @Test
    fun `comma selectors apply to each`() {
        val css = "h1, h2 { margin: 0 }"
        assertTrue("style=" in EmailRenderer.render("<h1>a</h1>", css))
        assertTrue("style=" in EmailRenderer.render("<h2>b</h2>", css))
    }

    @Test
    fun `css comments are ignored`() {
        val out = EmailRenderer.render("""<p class="c">x</p>""", "/* a comment */ .c { color: red }")
        assertTrue("color: red" in out, out)
    }

    @Test
    fun `empty declaration block produces no rule`() {
        assertFalse("style=" in EmailRenderer.render("""<p class="c">x</p>""", ".c { }"))
    }

    @Test
    fun `malformed declarations are skipped, valid ones kept`() {
        val out = EmailRenderer.render("""<p class="c">x</p>""", ".c { color: red; bogus; : v; pad: }")
        assertTrue("color: red" in out, out)
        assertFalse("bogus" in out, out)
    }

    @Test
    fun `unclosed rule is ignored`() {
        assertEquals("<p>x</p>", EmailRenderer.render("<p>x</p>", ".c { color: red"))
    }

    @Test
    fun `self-closing tags are inlined and kept self-closing`() {
        val out = EmailRenderer.render("""<img class="c"/>""", ".c { border: 0 }")
        assertTrue("border: 0" in out && out.trimEnd().endsWith("/>"), out)
    }

    @Test
    fun `non-tag angle brackets are left alone`() {
        // `<!-- -->` and `<3` aren't element starts (next char isn't a letter) → passed through verbatim.
        assertEquals("<!-- c --><3", EmailRenderer.render("<!-- c --><3", ".c { color: red }"))
    }

    @Test
    fun `combinator selectors are unsupported and do not inline`() {
        assertFalse("style=" in EmailRenderer.render("""<p class="c">x</p>""", "div > .c { color: red }"))
    }

    @Test
    fun `non-matching rule leaves the tag untouched`() {
        assertEquals("<p>x</p>", EmailRenderer.render("<p>x</p>", ".other { color: red }"))
    }

    @Test
    fun `existing inline style wins on conflict`() {
        val out = EmailRenderer.render("""<p class="c" style="color: blue">x</p>""", ".c { color: red }")
        assertTrue("color: blue" in out && "color: red" !in out, out)
    }

    @Test
    fun `id selector inlines`() {
        assertTrue("style=" in EmailRenderer.render("""<p id="hero">x</p>""", "#hero { color: red }"))
    }

    @Test
    fun `at-rules are retained as a style block while plain rules still inline`() {
        val out = EmailRenderer.render(
            """<html><head><title>t</title></head><body><p class="c">x</p></body></html>""",
            ".c { color: red } @media (max-width: 600px) { .c { font-size: 18px } }",
        )
        assertTrue("""<p class="c" style="color: red">""" in out, out)
        assertTrue("<style>@media (max-width: 600px) { .c { font-size: 18px } }</style></head>" in out, out)
        // The inlined rule is NOT re-emitted in the retained block.
        assertFalse("color: red }</style>" in out, out)
    }

    @Test
    fun `retained at-rules prepend when there is no head`() {
        val out = EmailRenderer.render("<p>x</p>", "@media print { p { display: none } }")
        assertTrue(out.startsWith("<style>@media print"), out)
        assertTrue(out.endsWith("<p>x</p>"), out)
    }

    @Test
    fun `statement at-rules like @import are retained too`() {
        val out = EmailRenderer.render(
            """<p class="c">x</p>""",
            "@import url('fonts.css'); .c { color: red }",
        )
        assertTrue("@import url('fonts.css');" in out, out)
        assertTrue("""style="color: red"""" in out, out)
    }

    @Test
    fun `a font-face block does not corrupt the plain rules around it`() {
        val out = EmailRenderer.render(
            """<p class="a">x</p><p class="b">y</p>""",
            ".a { color: red } @font-face { font-family: G; src: url(g.woff2) } .b { color: blue }",
        )
        assertTrue("""<p class="a" style="color: red">""" in out, out)
        assertTrue("""<p class="b" style="color: blue">""" in out, out)
        assertTrue("@font-face" in out, out)
    }

    @Test
    fun `parseCss exposes parsed rules`() {
        val rules = EmailRenderer.parseCss(".a, .b { color: red; size: 2 }")
        assertEquals(1, rules.size)
        assertEquals(listOf(".a", ".b"), rules[0].selectors)
        assertEquals(2, rules[0].declarations.size)
    }
}
