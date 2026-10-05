package bosca.bml.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlParserTest {

    private fun parse(src: String): ParseResult = BmlParser.parse(src)

    private fun nodes(src: String): List<Node> {
        val r = parse(src)
        assertFalse(r.hasErrors, "unexpected diagnostics: ${r.diagnostics}")
        return r.document.nodes
    }

    private fun single(src: String): Node = nodes(src).single { it !is TextNode || it.value.isNotBlank() }

    private fun StaticAttribute.parts(): List<AttrPart> = requireNotNull(value) { "expected an attribute value" }

    @Test
    fun `element with text`() {
        val el = assertIs<ElementNode>(single("<p>Hello</p>"))
        assertEquals("p", el.name)
        assertNull(el.namespace)
        assertFalse(el.selfClosing)
        val text = assertIs<TextNode>(el.children.single())
        assertEquals("Hello", text.value)
    }

    @Test
    fun `self closing and void elements`() {
        assertTrue(assertIs<ElementNode>(single("<br/>")).selfClosing)
        val void = assertIs<ElementNode>(single("<br>"))
        assertFalse(void.selfClosing)
        assertTrue(void.children.isEmpty())
    }

    @Test
    fun `qualified html namespace name`() {
        val el = assertIs<ElementNode>(single("<html:a>x</html:a>"))
        assertEquals("html", el.namespace)
        assertEquals("a", el.name)
        assertEquals("html:a", el.qualifiedName)
    }

    @Test
    fun `static bound spread and boolean attributes`() {
        val el = assertIs<ElementNode>(single("""<a class="x y-{ z }" :href="u.url" data-x {...rest} hidden>t</a>"""))
        val cls = assertIs<StaticAttribute>(el.attributes[0])
        assertEquals("class", cls.name)
        val clsParts = cls.parts()
        assertEquals(2, clsParts.size)
        assertEquals(AttrText("x y-"), clsParts[0])
        assertEquals(AttrInterpolation("z", false), clsParts[1])

        val href = assertIs<BoundAttribute>(el.attributes[1])
        assertEquals("href", href.name)
        assertEquals("u.url", href.expression)

        val dataX = assertIs<StaticAttribute>(el.attributes[2])
        assertEquals("data-x", dataX.name)
        assertNull(dataX.value)

        val spread = assertIs<SpreadAttribute>(el.attributes[3])
        assertEquals("rest", spread.expression)

        val hidden = assertIs<StaticAttribute>(el.attributes[4])
        assertEquals("hidden", hidden.name)
        assertNull(hidden.value)
    }

    @Test
    fun `bound and event expressions keep kotlin string literals inside brackets`() {
        val el = assertIs<ElementNode>(
            single("""<a :aria-label="t("nav.close")" :x="mapOf("a" to 1, "b" to 2)" @click="go("home", 'x')">t</a>"""),
        )
        assertEquals("""t("nav.close")""", assertIs<BoundAttribute>(el.attributes[0]).expression)
        assertEquals("""mapOf("a" to 1, "b" to 2)""", assertIs<BoundAttribute>(el.attributes[1]).expression)
        assertEquals("""go("home", 'x')""", assertIs<EventAttribute>(el.attributes[2]).expression)
    }

    @Test
    fun `a depth-zero delimiter quote closes the bound value and escapes still work`() {
        val el = assertIs<ElementNode>(single("""<a :x="\"a\" + b" :y='"c" + d' href="#">t</a>"""))
        assertEquals("\"a\" + b", assertIs<BoundAttribute>(el.attributes[0]).expression)
        assertEquals("\"c\" + d", assertIs<BoundAttribute>(el.attributes[1]).expression, "single-quote delimiters free double-quoted literals")
    }

    @Test
    fun `attribute interpolations keep quoted calls whole`() {
        val el = assertIs<ElementNode>(single("""<a title="{ t("nav.help") } — help">t</a>"""))
        val parts = assertIs<StaticAttribute>(el.attributes[0]).parts()
        assertEquals(AttrInterpolation("""t("nav.help")""", false), parts[0])
        assertEquals(AttrText(" — help"), parts[1])
    }

    @Test
    fun `interpolation escaped and raw`() {
        val escaped = assertIs<InterpolationNode>(single("{ user.name }"))
        assertEquals("user.name", escaped.expression)
        assertFalse(escaped.raw)

        val raw = assertIs<InterpolationNode>(single("{@ trustedHtml }"))
        assertEquals("trustedHtml", raw.expression)
        assertTrue(raw.raw)
    }

    @Test
    fun `text escapes`() {
        val text = assertIs<TextNode>(single("a \\{ b \\< c \\\\ d"))
        assertEquals("a { b < c \\ d", text.value)
    }

    @Test
    fun `html and bml comments`() {
        val html = assertIs<CommentNode>(single("<!-- hi -->"))
        assertTrue(html.emitted)
        assertEquals(" hi ", html.text)

        val bml = assertIs<CommentNode>(single("{# note #}"))
        assertFalse(bml.emitted)
        assertEquals(" note ", bml.text)
    }

    @Test
    fun `server script with provides captured verbatim`() {
        val raw = assertIs<RawTextNode>(single("""<script server provides="listView">bosca.query(GetListView(id = id)).list</script>"""))
        assertEquals(RawKind.ServerScript, raw.kind)
        assertEquals("bosca.query(GetListView(id = id)).list", raw.content)
        val provides = assertIs<StaticAttribute>(raw.attributes.first { it.name == "provides" })
        assertEquals(AttrText("listView"), provides.parts().single())
    }

    @Test
    fun `client script contract and style raw regions`() {
        assertEquals(RawKind.ClientScript, assertIs<RawTextNode>(single("<script client>const x = 1 < 2;</script>")).kind)
        assertEquals(RawKind.Contract, assertIs<RawTextNode>(single("<contract>interface X { suspend fun f(): Int }</contract>")).kind)
        val style = assertIs<RawTextNode>(single("<style>.a { color: red; }</style>"))
        assertEquals(RawKind.Style, style.kind)
        assertEquals(".a { color: red; }", style.content)
    }

    @Test
    fun `for plain and indexed bindings`() {
        val plain = assertIs<ForNode>(single("<for item in listView.items>{ item.label }</for>"))
        assertEquals("item", plain.binding.item)
        assertNull(plain.binding.indexOrKey)
        assertEquals("listView.items", plain.iterable)

        val indexed = assertIs<ForNode>(single("<for (i, item) in items>{ i }</for>"))
        assertEquals("item", indexed.binding.item)
        assertEquals("i", indexed.binding.indexOrKey)
    }

    @Test
    fun `if elseif else single block`() {
        val ifNode = assertIs<IfNode>(
            single(
                """
                <if items.isEmpty()>
                  <p>empty</p>
                <else-if items.size == 1>
                  <p>one</p>
                <else>
                  <p>many</p>
                </if>
                """.trimIndent()
            )
        )
        assertEquals(2, ifNode.branches.size)
        assertEquals("items.isEmpty()", ifNode.branches[0].condition)
        assertEquals("items.size == 1", ifNode.branches[1].condition)
        assertTrue(ifNode.elseChildren != null)
    }

    @Test
    fun `if condition with parenthesized comparison`() {
        val ifNode = assertIs<IfNode>(single("<if (a > b)><p>x</p></if>"))
        assertEquals("(a > b)", ifNode.branches.single().condition)
        assertNull(ifNode.elseChildren)
    }

    @Test
    fun `feature flag if supports boolean variation and evaluation predicates`() {
        val boolean = assertIs<IfNode>(single("""<if flag="new-checkout">on<else>off</if>"""))
        assertEquals("ctx.featureFlags.enabled(\"new-checkout\")", boolean.branches.single().condition)

        val variation = assertIs<IfNode>(single("""<if flag="checkout-layout" variation="compact">on</if>"""))
        assertEquals(
            "ctx.featureFlags.variation(\"checkout-layout\", \"compact\")",
            variation.branches.single().condition,
        )

        val predicate = assertIs<IfNode>(
            single("""<if flag="checkout" :when='flag.variationKey == "compact" && !flag.degraded'>on</if>"""),
        )
        assertEquals(
            "ctx.featureFlags.evaluate(\"checkout\").let { flag -> (flag.variationKey == \"compact\" && !flag.degraded) }",
            predicate.branches.single().condition,
        )
    }

    @Test
    fun `conditional branch retains the tagged three-property JVM API`() {
        ConditionalBranch::class.java.getDeclaredConstructor(
            String::class.java,
            List::class.java,
            Span::class.java,
        )
        ConditionalBranch::class.java.getDeclaredMethod(
            "copy",
            String::class.java,
            List::class.java,
            Span::class.java,
        )
    }

    @Test
    fun `feature flag syntax works in else-if while a flag variable remains kotlin`() {
        val node = assertIs<IfNode>(
            single("""<if flag>variable<else-if flag="rollout" variation="on">rollout</if>"""),
        )
        assertEquals("flag", node.branches[0].condition)
        assertEquals("ctx.featureFlags.variation(\"rollout\", \"on\")", node.branches[1].condition)
    }

    @Test
    fun `flag equality and referential equality remain ordinary kotlin`() {
        listOf(
            "flag==\"control\"",
            "flag == \"control\"",
            "flag === other",
        ).forEach { expression ->
            val node = assertIs<IfNode>(single("<if $expression>on</if>"))
            assertEquals(expression, node.branches.single().condition)
        }
    }

    @Test
    fun `nested elements and islands`() {
        val island = assertIs<ElementNode>(
            single("""<island name="sortable" client="ts" :id="id"><ul data-items><li>x</li></ul></island>""")
        )
        assertEquals("island", island.name)
        val nameAttr = assertIs<StaticAttribute>(island.attributes[0])
        assertEquals("sortable", (nameAttr.parts().single() as AttrText).value)
        assertEquals("id", assertIs<BoundAttribute>(island.attributes[2]).name)
        val ul = assertIs<ElementNode>(island.children.single { it is ElementNode })
        assertEquals("ul", ul.name)
    }

    @Test
    fun `spans track line and column`() {
        val el = assertIs<ElementNode>(single("\n  <p>hi</p>"))
        assertEquals(2, el.span.startLine)
        assertEquals(3, el.span.startColumn)
    }

    // ── error recovery ───────────────────────────────────────────────────────

    @Test
    fun `unterminated interpolation reports error`() {
        val r = parse("<p>{ a.b </p>")
        assertTrue(r.hasErrors)
        assertTrue(r.diagnostics.any { it.message.contains("Unterminated expression") })
    }

    @Test
    fun `unterminated raw region reports error`() {
        val r = parse("<style>.a { color: red; }")
        assertTrue(r.hasErrors)
        assertTrue(r.diagnostics.any { it.message.contains("Unterminated <style>") })
    }

    @Test
    fun `mismatched close tag recovers`() {
        val r = parse("<a><b>x</a>")
        assertTrue(r.hasErrors)
        // best-effort tree: <a> contains <b>
        val a = assertIs<ElementNode>(r.document.nodes.single())
        assertEquals("a", a.name)
        assertEquals("b", assertIs<ElementNode>(a.children.single()).name)
    }

    @Test
    fun `script must be server or client`() {
        val r = parse("<script>x</script>")
        assertTrue(r.hasErrors)
        assertTrue(r.diagnostics.any { it.message.contains("server") })
    }
}
