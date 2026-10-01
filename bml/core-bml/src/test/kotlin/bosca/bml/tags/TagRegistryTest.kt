package bosca.bml.tags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TagRegistryTest {

    private fun registry() = DefaultTagRegistry()

    @Test
    fun `resolves built-in html elements`() {
        val r = assertIs<TagResolution.Html>(registry().resolve("div"))
        assertEquals("div", r.name)
        assertEquals(false, r.isVoid)
    }

    @Test
    fun `resolves void html elements`() {
        assertTrue(assertIs<TagResolution.Html>(registry().resolve("br")).isVoid)
        assertTrue(assertIs<TagResolution.Html>(registry().resolve("img")).isVoid)
    }

    @Test
    fun `resolves the complete SVG filter vocabulary and svg namespace`() {
        listOf("filter", "feColorMatrix", "feComponentTransfer", "feFuncR", "feFuncG", "feFuncB")
            .forEach { assertIs<TagResolution.Html>(registry().resolve(it)) }
        assertIs<TagResolution.Html>(registry().resolve("use", "svg"))
        assertIs<TagResolution.Error>(registry().resolve("notAnSvgElement", "svg"))
    }

    @Test
    fun `resolves special tags and they win over html`() {
        assertIs<TagResolution.Special>(registry().resolve("page"))
        assertIs<TagResolution.Special>(registry().resolve("route"))
        assertIs<TagResolution.Special>(registry().resolve("island"))
        assertIs<TagResolution.Special>(registry().resolve("for"))
        assertIs<TagResolution.Special>(registry().resolve("if"))
        assertIs<TagResolution.Special>(registry().resolve("inject"))
        // names BML reuses from HTML still resolve as special, not html
        assertIs<TagResolution.Special>(registry().resolve("template"))
        assertIs<TagResolution.Special>(registry().resolve("slot"))
    }

    @Test
    fun `registers a new custom tag`() {
        val reg = registry()
        assertEquals(RegisterResult.Ok, reg.register(CustomTag(tag = "card", overrides = false, props = listOf(TagProp("title", "String", required = true, hasDefault = false)))))
        val r = assertIs<TagResolution.Custom>(reg.resolve("card"))
        assertEquals("card", r.decl.tag)
        assertEquals(1, r.decl.props.size)
    }

    @Test
    fun `override of a built-in resolves to custom, html namespace bypasses it`() {
        val reg = registry()
        reg.register(CustomTag(tag = "a", overrides = true))
        assertIs<TagResolution.Custom>(reg.resolve("a"))
        // the html: escape hatch reaches the native anchor without recursing
        val native = assertIs<TagResolution.Html>(reg.resolve("a", namespace = "html"))
        assertEquals("a", native.name)
    }

    @Test
    fun `cannot override a protected tag`() {
        val rejected = assertIs<RegisterResult.Rejected>(registry().register(CustomTag(tag = "island", overrides = true)))
        assertTrue(rejected.reason.contains("protected"))
        // and resolution still treats it as special
        assertIs<TagResolution.Special>(registry().resolve("island"))
    }

    @Test
    fun `unknown tag is an error`() {
        val r = assertIs<TagResolution.Error>(registry().resolve("rating-stars"))
        assertTrue(r.message.contains("Unknown tag"))
    }

    @Test
    fun `unknown html namespace member is an error`() {
        assertIs<TagResolution.Error>(registry().resolve("nope", namespace = "html"))
    }

    @Test
    fun `unknown namespace is an error`() {
        val r = assertIs<TagResolution.Error>(registry().resolve("a", namespace = "math"))
        assertTrue(r.message.contains("namespace"))
    }
}
