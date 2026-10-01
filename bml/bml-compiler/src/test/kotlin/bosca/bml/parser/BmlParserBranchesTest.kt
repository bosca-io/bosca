package bosca.bml.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Branch/error-path coverage for [BmlParser] — the recovery paths, escapes, every attribute form, the
 * control-flow variants, raw-text termination, and the expression scanners. (Happy paths live in
 * [BmlParserTest]; this file targets the diagnostics + edge branches.)
 */
class BmlParserBranchesTest {

    private fun errors(src: String): List<String> =
        BmlParser.parse(src).diagnostics.filter { it.severity == Severity.Error }.map { it.message }

    private fun nodes(src: String): List<Node> = BmlParser.parse(src).document.nodes
    private inline fun <reified T> first(src: String): T = nodes(src).filterIsInstance<T>().first()

    // ── document / tag-name recovery ────────────────────────────────────────

    @Test fun `stray closing tag at top level is reported`() {
        assertTrue(errors("</div>").any { "Unexpected closing tag at top level" in it })
    }

    @Test fun `lone less-than with no name becomes literal text`() {
        val r = BmlParser.parse("a < b")
        assertTrue(r.diagnostics.any { it.message.contains("Expected a tag name") })
        assertTrue(r.document.nodes.any { it is TextNode && it.value == "<" })
    }

    // ── text escapes ────────────────────────────────────────────────────────

    @Test fun `backslash escapes unwrap brace, angle, and backslash`() {
        assertEquals("a{b}c<d\\e", first<TextNode>("""a\{b\}c\<d\\e""").value)
    }

    @Test fun `a non-escape backslash is kept literally`() {
        assertEquals("a\\nb", first<TextNode>("""a\nb""").value)
    }

    // ── comments ──────────────────────────────────────────────────────────────

    @Test fun `html comment is emitted, bml comment is build-only`() {
        assertTrue(first<CommentNode>("<!-- hi -->").emitted)
        assertFalse(first<CommentNode>("{# hi #}").emitted)
    }

    @Test fun `unterminated comments are reported`() {
        assertTrue(errors("<!-- nope").any { "Unterminated comment" in it })
        assertTrue(errors("{# nope").any { "Unterminated comment" in it })
    }

    // ── interpolation ─────────────────────────────────────────────────────────

    @Test fun `raw vs escaped interpolation`() {
        assertTrue(first<InterpolationNode>("{@ x }").raw)
        assertFalse(first<InterpolationNode>("{ x }").raw)
    }

    @Test fun `interpolation balances nested braces and strings`() {
        assertEquals("""f(mapOf("a" to 1), { it })""", first<InterpolationNode>("""{ f(mapOf("a" to 1), { it }) }""").expression)
    }

    @Test fun `unterminated interpolation is reported`() {
        assertTrue(errors("{ x").any { "Unterminated expression" in it })
    }

    // ── attribute forms ───────────────────────────────────────────────────────

    @Test fun `bound, event, spread, boolean, quoted, and unquoted attributes parse`() {
        val el = first<ElementNode>("""<a :href="u" @click="f" {...rest} disabled id=x title="t">z</a>""")
        assertTrue(el.attributes.any { it is BoundAttribute && it.name == "href" && it.expression == "u" })
        assertTrue(el.attributes.any { it is EventAttribute && it.event == "click" && it.expression == "f" })
        assertTrue(el.attributes.any { it is SpreadAttribute && it.expression == "rest" })
        assertTrue(el.attributes.any { it is StaticAttribute && it.name == "disabled" && it.value == null })
        assertTrue(el.attributes.any { it is StaticAttribute && it.name == "id" }) // unquoted
        assertTrue(el.attributes.any { it is StaticAttribute && it.name == "title" })
    }

    @Test fun `single-quoted and interpolated attribute values parse`() {
        val el = first<ElementNode>("""<a class='c-{ x }-{@ y }'>z</a>""")
        val parts = (el.attributes.first() as StaticAttribute).value!!
        assertTrue(parts.any { it is AttrText && it.value == "c-" })
        assertTrue(parts.any { it is AttrInterpolation && !it.raw && it.expression == "x" })
        assertTrue(parts.any { it is AttrInterpolation && it.raw && it.expression == "y" })
    }

    @Test fun `escapes inside quoted attribute values are unwrapped`() {
        val el = first<ElementNode>("""<a title="a\"b">z</a>""")
        assertEquals("""a"b""", ((el.attributes.first() as StaticAttribute).value!!.first() as AttrText).value)
    }

    @Test fun `missing = after bound and event attributes is reported`() {
        assertTrue(errors("""<a :href>z</a>""").any { "Expected '=' after :href" in it })
        assertTrue(errors("""<a @click>z</a>""").any { "Expected '=' after @click" in it })
    }

    @Test fun `spread without dots is reported`() {
        assertTrue(errors("""<a { rest }>z</a>""").any { "Expected '...' in spread" in it })
    }

    @Test fun `bound value that is not quoted is reported`() {
        assertTrue(errors("""<a :href=u>z</a>""").any { "Expected a quoted expression" in it })
    }

    @Test fun `unterminated quoted expression is reported`() {
        assertTrue(errors("""<a :href="u>z""").any { "Unterminated quoted expression" in it })
    }

    // ── elements: void / self-closing / namespaces / close errors ─────────────

    @Test fun `void elements take no children`() {
        val br = first<ElementNode>("<br>after")
        assertTrue(br.children.isEmpty() && !br.selfClosing)
        assertTrue(nodes("<br>after").any { it is TextNode && it.value == "after" })
    }

    @Test fun `self-closing and namespaced elements`() {
        assertTrue(first<ElementNode>("<img/>").selfClosing)
        val ns = first<ElementNode>("<html:button>x</html:button>")
        assertEquals("html", ns.namespace); assertEquals("button", ns.name)
    }

    @Test fun `missing gt to close a tag is reported`() {
        assertTrue(errors("<div class=\"x\"<span>").any { "Expected '>' to close <div>" in it })
    }

    @Test fun `missing and mismatched closing tags are reported`() {
        assertTrue(errors("<div>hi").any { "Missing closing tag </div>" in it })
        assertTrue(errors("<div>hi</span>").any { "Missing closing tag </div>" in it })
    }

    // ── raw-text regions ──────────────────────────────────────────────────────

    @Test fun `script must declare server or client`() {
        assertTrue(errors("<script>x</script>").any { "must be declared 'server' or 'client'" in it })
        assertEquals(RawKind.ServerScript, first<RawTextNode>("<script server>x</script>").kind)
        assertEquals(RawKind.ClientScript, first<RawTextNode>("<script client>x</script>").kind)
    }

    @Test fun `contract and style raw elements capture content verbatim`() {
        assertEquals(RawKind.Contract, first<RawTextNode>("<contract>type X</contract>").kind)
        val style = first<RawTextNode>("<style>.a { color: red }</style>")
        assertEquals(RawKind.Style, style.kind)
        assertEquals(".a { color: red }", style.content)
    }

    @Test fun `unterminated raw element is reported`() {
        assertTrue(errors("<style>.a{}").any { "Unterminated <style>" in it })
    }

    // ── control flow ──────────────────────────────────────────────────────────

    @Test fun `for with index binding and plain binding`() {
        val indexed = first<ForNode>("<for (i, x) in items>a</for>")
        assertEquals("i", indexed.binding.indexOrKey); assertEquals("x", indexed.binding.item)
        val plain = first<ForNode>("<for x in items>a</for>")
        assertEquals(null, plain.binding.indexOrKey); assertEquals("x", plain.binding.item)
    }

    @Test fun `for reports a missing in keyword`() {
        assertTrue(errors("<for x items>a</for>").any { "Expected 'in' in <for>" in it })
    }

    @Test fun `for binding reports missing comma and paren`() {
        assertTrue(errors("<for (i x) in items>a</for>").any { "Expected ',' in for binding" in it })
        assertTrue(errors("<for (i, x in items>a</for>").any { "Expected ')' in for binding" in it })
    }

    @Test fun `if else-if else chain parses`() {
        val node = first<IfNode>("<if a>A<else-if b>B<else>C</if>")
        assertEquals(2, node.branches.size)
        assertEquals("a", node.branches[0].condition)
        assertEquals("b", node.branches[1].condition)
        assertTrue(node.elseChildren != null)
    }

    @Test fun `self-closing else and else with trailing space parse`() {
        assertTrue(first<IfNode>("<if a>A<else/>B</if>").elseChildren != null)
        assertTrue(first<IfNode>("<if a>A<else >B</if>").elseChildren != null)
    }

    @Test fun `else without if is reported`() {
        assertTrue(errors("<else>x</else>").any { "'<else>' without a matching '<if>'" in it })
    }

    @Test fun `feature flag if rejects dynamic blank conflicting and unsupported attributes`() {
        assertTrue(errors("""<if flag="{ key }">x</if>""").any { "must be a non-empty literal" in it })
        assertTrue(errors("""<if flag="">x</if>""").any { "must be a non-empty literal" in it })
        assertTrue(
            errors("""<if flag="layout" variation="a" :when="flag.degraded">x</if>""")
                .any { "cannot combine" in it },
        )
        assertTrue(errors("""<if flag="layout" fallback="a">x</if>""").any { "Unsupported" in it })
    }

    // ── header expression scanner ─────────────────────────────────────────────

    @Test fun `header expr respects comparison operators, arrows, and brackets`() {
        assertEquals("a >= b", first<IfNode>("<if a >= b>x</if>").branches[0].condition)
        assertEquals("items.filter { it -> it > 0 }", first<ForNode>("<for x in items.filter { it -> it > 0 }>a</for>").iterable)
        // a '>' inside parens/brackets is depth-tracked and kept; only a bare top-level '>' closes the tag
        assertEquals("(list[0] > 1)", first<IfNode>("<if (list[0] > 1)>x</if>").branches[0].condition)
    }

    @Test fun `header expr skips string literals including triple-quoted`() {
        assertEquals("""x == ">"""", first<IfNode>("""<if x == ">">y</if>""").branches[0].condition)
        // the '>' inside the triple-quoted string is skipped; the bare '>' after .length closes the tag
        assertEquals("\"\"\"a>b\"\"\".length", first<IfNode>("<if \"\"\"a>b\"\"\".length > 0>y</if>").branches[0].condition)
    }
}
