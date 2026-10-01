package bosca.bml.codegen

import bosca.bml.parser.BmlParser
import bosca.bml.parser.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `t` / `t:count` / `t:<attr>` markup surface: folding, placeholder
 * naming, manifest harvesting, and every compile-time validity rule.
 */
class BmlI18nCodegenTest {

    private fun generate(bml: String): CodeGenResult {
        val parsed = BmlParser.parse(bml)
        assertTrue(!parsed.hasErrors, "parse: ${parsed.diagnostics}")
        return BmlCodeGenerator("gen", "TPage", "t.bml").generate(parsed.document)
    }

    private fun errorsOf(result: CodeGenResult): List<String> =
        result.diagnostics.filter { it.severity == Severity.Error }.map { it.message }

    private fun page(body: String) = "<page route=\"/t\">$body</page>"

    // ── folding ──────────────────────────────────────────────────────────────

    @Test
    fun `folds text and interpolations into one template with named placeholders`() {
        val result = generate(page("""<h1 t="welcome.back">  Welcome back,
            { user.firstName }!  </h1>"""))
        assertEquals(emptyList(), errorsOf(result))
        assertTrue("resolveOr(ctx.messages, ctx.locale, \"welcome.back\", \"Welcome back, {firstName}!\"" in result.source, result.source)
        assertTrue("\"firstName\" to (user.firstName)" in result.source, result.source)
        val entry = result.i18n.single()
        assertEquals("welcome.back", entry.key)
        assertEquals("Welcome back, {firstName}!", entry.message)
        assertEquals(listOf("firstName" to "user.firstName"), entry.placeholders.map { it.name to it.expression })
        // The t attribute itself never reaches the output markup.
        assertTrue("t=\\\"" !in result.source, result.source)
    }

    @Test
    fun `complex expressions get positional placeholders and duplicates share names`() {
        val result = generate(page("""<p t="mix">{ a.b } and { total * 2 } and { a.b }</p>"""))
        assertEquals(emptyList(), errorsOf(result))
        assertEquals("{b} and {1} and {b}", result.i18n.single().message)
    }

    @Test
    fun `entities decode at compile time so w-text cannot double-escape`() {
        val result = generate(page("""<p t="amp.text">Fish &amp; chips &mdash; daily</p>"""))
        assertEquals(emptyList(), errorsOf(result))
        assertEquals("Fish & chips — daily", result.i18n.single().message)
    }

    // ── plurals ──────────────────────────────────────────────────────────────

    @Test
    fun `plural forms fold per category and the count expression becomes {count}`() {
        val result = generate(
            page(
                """<span t="cart.items" t:count="items.size">
                     <t:one>You have one item</t:one>
                     <t:other>You have { items.size } items</t:other>
                   </span>""",
            ),
        )
        assertEquals(emptyList(), errorsOf(result))
        val entry = result.i18n.single()
        assertTrue(entry.plural)
        assertEquals(mapOf("ONE" to "You have one item", "OTHER" to "You have {count} items"), entry.pluralForms)
        assertTrue("resolvePluralOr(ctx.messages, ctx.locale, \"cart.items\", (items.size).toLong()" in result.source, result.source)
    }

    @Test
    fun `shorthand plural treats the authored text as the OTHER form`() {
        val result = generate(page("""<span t="cart.items" t:count="n">You have { n } items</span>"""))
        assertEquals(emptyList(), errorsOf(result))
        assertEquals(mapOf("OTHER" to "You have {count} items"), result.i18n.single().pluralForms)
    }

    // ── attribute pairing ────────────────────────────────────────────────────

    @Test
    fun `paired attributes lower to runtime lookups with the authored value as fallback`() {
        val result = generate(page("""<input t:placeholder="search.hint" placeholder="Search here"/>"""))
        assertEquals(emptyList(), errorsOf(result))
        assertTrue(
            "w.attr(\"placeholder\", (bosca.bml.i18n.Messages.resolveOr(ctx.messages, ctx.locale, \"search.hint\", \"Search here\", emptyMap())))" in result.source,
            result.source,
        )
        assertEquals(BmlI18nOrigin.ATTRIBUTE, result.i18n.single().origin)
    }

    // ── validity rules ───────────────────────────────────────────────────────

    @Test
    fun `nested markup inside a t element is a compile error`() {
        val errors = errorsOf(generate(page("""<p t="x">Hello <b>bold</b></p>""")))
        assertTrue(errors.any { "only text and { } interpolations" in it }, "$errors")
    }

    @Test
    fun `raw interpolation inside a t element is a compile error`() {
        val errors = errorsOf(generate(page("""<p t="x">Hi {@ raw }</p>""")))
        assertTrue(errors.any { "raw" in it }, "$errors")
    }

    @Test
    fun `category children without a count binding are a compile error`() {
        val errors = errorsOf(generate(page("""<span t="k"><t:other>things</t:other></span>""")))
        assertTrue(errors.any { "count binding" in it }, "$errors")
    }

    @Test
    fun `a stray category form outside a t element is a compile error`() {
        val errors = errorsOf(generate(page("""<t:one>alone</t:one>""")))
        assertTrue(errors.any { "only valid inside" in it }, "$errors")
    }

    @Test
    fun `t-other is required when categories are present`() {
        val errors = errorsOf(
            generate(page("""<span t="k" t:count="n"><t:one>one thing</t:one></span>""")),
        )
        assertTrue(errors.any { "<t:other> is required" in it }, "$errors")
    }

    @Test
    fun `an unknown category name is a compile error`() {
        val errors = errorsOf(
            generate(page("""<span t="k" t:count="n"><t:some>x</t:some><t:other>y</t:other></span>""")),
        )
        assertTrue(errors.any { "not a CLDR plural category" in it }, "$errors")
    }

    @Test
    fun `t-count without a key is a compile error`() {
        val errors = errorsOf(generate(page("""<span t:count="n">things</span>""")))
        assertTrue(errors.any { "t:count requires t=" in it }, "$errors")
    }

    @Test
    fun `an unpaired t-attr is a compile error`() {
        val errors = errorsOf(generate(page("""<input t:placeholder="search.hint"/>""")))
        assertTrue(errors.any { "no matching placeholder attribute" in it }, "$errors")
    }

    @Test
    fun `the same key with different source text is a compile error, identical text dedupes`() {
        val dup = errorsOf(generate(page("""<p t="k">one text</p><p t="k">another text</p>""")))
        assertTrue(dup.any { "different source text" in it }, "$dup")

        val same = generate(page("""<p t="k">same text</p><p t="k">same text</p>"""))
        assertEquals(emptyList(), errorsOf(same))
        assertEquals(1, same.i18n.size, "identical declarations dedupe to one manifest entry")
    }

    @Test
    fun `a t element with no authored text is a compile error`() {
        val errors = errorsOf(generate(page("""<p t="k">   </p>""")))
        assertTrue(errors.any { "no authored text" in it }, "$errors")
    }

    @Test
    fun `t on a component instantiation is a compile error`() {
        val bml = """
            <component tag="card"><div><slot/></div></component>
            <page route="/t"><card t="k">text</card></page>
        """.trimIndent()
        val parsed = BmlParser.parse(bml)
        val result = BmlCodeGenerator("gen", "TPage", "t.bml").generate(parsed.document)
        assertTrue(errorsOf(result).any { "reserved on component instantiations" in it }, "${errorsOf(result)}")
    }
}
