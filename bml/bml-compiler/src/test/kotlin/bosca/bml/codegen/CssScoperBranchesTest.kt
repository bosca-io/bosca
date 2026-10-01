package bosca.bml.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Edge-branch coverage for [CssScoper]: at-rule kinds, combinators, pseudos, and string/comment scanning. */
class CssScoperBranchesTest {

    private val A = """[data-bml-c="c"]"""
    private fun s(css: String) = CssScoper.scope(css, "c")

    @Test fun `statement at-rules are copied verbatim`() {
        assertEquals("""@import "reset.css";""", s("""@import "reset.css";"""))
        assertEquals("""@charset "utf-8";""", s("""@charset "utf-8";"""))
    }

    @Test fun `sibling combinators scope only the last compound`() {
        assertEquals(".a + .b$A { x: y }", s(".a + .b { x: y }"))
        assertEquals(".a ~ .b$A { x: y }", s(".a ~ .b { x: y }"))
    }

    @Test fun `supports and layer group at-rules recurse`() {
        val sup = s("@supports (display: grid) { .a { x: y } }")
        assertTrue(sup.startsWith("@supports (display: grid) {") && ".a$A { x: y }" in sup, sup)
        val layer = s("@layer base { .a { x: y } }")
        assertTrue(layer.startsWith("@layer base {") && ".a$A { x: y }" in layer, layer)
    }

    @Test fun `page at-rule block is not selector-scoped`() {
        assertEquals("@page { margin: 0 }", s("@page { margin: 0 }"))
    }

    @Test fun `trailing content with no rule is preserved`() {
        assertEquals("/* only a comment */", s("/* only a comment */"))
    }

    @Test fun `scope is inserted before a functional pseudo`() {
        assertEquals(".a$A:not(.b) { x: y }", s(".a:not(.b) { x: y }"))
    }

    @Test fun `attribute-selector compound gets the scope appended`() {
        assertEquals("""input[type="text"]$A { x: y }""", s("""input[type="text"] { x: y }"""))
    }

    @Test fun `a closing brace inside a declaration string does not end the block`() {
        assertEquals(""".a$A { content: "}" }""", s(""".a { content: "}" }"""))
    }

    @Test fun `comments between rules are skipped and both rules scope`() {
        val out = s(".a { x: y } /* gap */ .b { z: w }")
        assertTrue(".a$A { x: y }" in out, out)
        assertTrue(".b$A { z: w }" in out, out)
    }

    @Test fun `nested group at-rules recurse all the way down`() {
        val out = s("@media screen { @supports (x:1) { .a { p: q } } }")
        assertTrue(out.startsWith("@media screen {") && "@supports (x:1) {" in out && ".a$A { p: q }" in out, out)
    }

    @Test fun `leading empty selector segment is tolerated`() {
        // a stray leading comma yields an empty selector that is left empty, the real one still scopes
        assertEquals(", .a$A { x: y }", s(", .a { x: y }"))
    }

    @Test fun `escaped quote inside an attribute selector string is handled`() {
        assertEquals("""a[title="q\"x"]$A { x: y }""", s("""a[title="q\"x"] { x: y }"""))
    }
}
