package bosca.bml.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CssScoperTest {

    private fun scope(css: String) = CssScoper.scope(css, "badge")
    private val s = """[data-bml-c="badge"]"""

    @Test
    fun `simple class selector gets the scope attribute`() {
        assertEquals(""".badge$s { color: red; }""", scope(".badge { color: red; }"))
    }

    @Test
    fun `element selector is scoped`() {
        assertEquals("""span$s { display: inline; }""", scope("span { display: inline; }"))
    }

    @Test
    fun `comma-separated selector list scopes each selector`() {
        assertEquals(""".a$s, .b$s { x: 1; }""", scope(".a, .b { x: 1; }"))
    }

    @Test
    fun `descendant combinator scopes only the last compound`() {
        assertEquals(""".list li$s { x: 1; }""", scope(".list li { x: 1; }"))
    }

    @Test
    fun `child combinator scopes only the last compound`() {
        assertEquals(""".a > .b$s { x: 1; }""", scope(".a > .b { x: 1; }"))
    }

    @Test
    fun `pseudo-class keeps the scope before it`() {
        assertEquals(""".btn$s:hover { x: 1; }""", scope(".btn:hover { x: 1; }"))
    }

    @Test
    fun `pseudo-element keeps the scope before it`() {
        assertEquals(""".btn$s::before { content: ""; }""", scope(""".btn::before { content: ""; }"""))
    }

    @Test
    fun `compound of class and class scopes once at the end`() {
        assertEquals(""".a.b$s { x: 1; }""", scope(".a.b { x: 1; }"))
    }

    @Test
    fun `attribute selector value with spaces is not split as a combinator`() {
        // The space inside [title="a b"] must not be read as a descendant combinator.
        assertEquals("""a[title="a b"]$s { x: 1; }""", scope("""a[title="a b"] { x: 1; }"""))
    }

    @Test
    fun `media query recurses and scopes inner rules but not the at-rule`() {
        val out = scope("@media (max-width: 600px) { .a { x: 1; } }")
        assertTrue(out.startsWith("@media (max-width: 600px) {"), "at-rule prelude preserved: $out")
        assertTrue(""".a$s { x: 1; }""" in out, "inner rule scoped: $out")
    }

    @Test
    fun `keyframes are left completely unscoped`() {
        val out = scope("@keyframes spin { from { opacity: 0; } to { opacity: 1; } }")
        assertTrue("from {" in out && "to {" in out, "keyframe selectors preserved: $out")
        assertTrue(s !in out, "keyframe selectors must NOT be scoped: $out")
    }

    @Test
    fun `font-face is left unscoped`() {
        val out = scope("""@font-face { font-family: "X"; src: url(x.woff2); }""")
        assertTrue(s !in out, "@font-face has no selector to scope: $out")
        assertTrue("font-family" in out, "declarations preserved: $out")
    }

    @Test
    fun `multiple rules each get scoped`() {
        val out = scope(".a { x: 1; } .b { y: 2; }")
        assertTrue(""".a$s { x: 1; }""" in out && """.b$s { y: 2; }""" in out, out)
    }

    @Test
    fun `declaration values are preserved verbatim`() {
        // A colon inside a value (url, data URI) must not be mistaken for a pseudo.
        val out = scope(""".x { background: url("a:b"); }""")
        assertTrue(""".x$s {""" in out && """url("a:b")""" in out, out)
    }
}
