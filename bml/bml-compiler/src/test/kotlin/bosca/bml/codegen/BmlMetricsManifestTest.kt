package bosca.bml.codegen

import bosca.bml.parser.BmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmlMetricsManifestTest {

    private fun gen(src: String, obj: String = "XPage", components: Map<String, String> = emptyMap()): CodeGenResult {
        val parsed = BmlParser.parse(src)
        assertFalse(parsed.hasErrors, "parse errors: ${parsed.diagnostics}")
        return BmlCodeGenerator(packageName = "test", objectName = obj, sourcePath = "x.bml", components = components)
            .generate(parsed.document)
    }

    @Test
    fun `page meta captures route, client module, and component refs`() {
        val result = gen(
            """<page route="/lists/{id}">
              |<script client>console.log(1)</script>
              |<card title="t">x</card>
              |</page>""".trimMargin(),
            components = mapOf("card" to "CardComponent"),
        )
        val meta = result.pageMeta ?: error("no page meta")
        assertEquals("XPage", meta.objectName)
        assertEquals("/lists/{id}", meta.route)
        assertEquals("XPage.js", meta.clientModule)
        assertEquals(listOf("card"), meta.componentTags)
    }

    @Test
    fun `page without client script has no module`() {
        val result = gen("""<page route="/">plain</page>""")
        assertNull(result.pageMeta?.clientModule)
    }

    @Test
    fun `component meta captures scoped styles, deps, and client module`() {
        val result = gen(
            """<component tag="card">
              |<prop name="title" type="String" required/>
              |<script client>console.log(2)</script>
              |<badge/>
              |<div class="card">{ title }</div>
              |<style scoped>.card { margin: 0 }</style>
              |</component>""".trimMargin(),
            obj = "CardPage",
            components = mapOf("badge" to "BadgeComponent"),
        )
        val meta = result.componentMeta.single()
        assertEquals("card", meta.tag)
        assertTrue(".card" in meta.styles && "[data-bml-c=" in meta.styles, "expected scoped css, got: ${meta.styles}")
        assertEquals(listOf("badge"), meta.deps)
        assertEquals("CardPage.js", meta.clientModule)
    }

    @Test
    fun `closure expands transitively through deps`() {
        val components = listOf(
            BmlComponentMeta("a", ".a{}", deps = listOf("b"), clientModule = null),
            BmlComponentMeta("b", "", deps = listOf("c"), clientModule = "BPage.js"),
            BmlComponentMeta("c", ".c{}", deps = emptyList(), clientModule = null),
        )
        val closure = BmlMetricsManifest.closure(listOf("a"), components.associateBy { it.tag })
        assertEquals(setOf("a", "b", "c"), closure)
    }

    @Test
    fun `render resolves css and js columns, excluding the page's own module`() {
        val pages = listOf(
            BmlPageMeta("HomePage", "/", "HomePage.js", listOf("a")),
            BmlPageMeta("AboutPage", "/about", null, emptyList()),
        )
        val components = listOf(
            BmlComponentMeta("a", ".a{}", deps = listOf("b"), clientModule = "HomePage.js"),
            BmlComponentMeta("b", ".b{}", deps = emptyList(), clientModule = "SharedPage.js"),
        )
        val text = BmlMetricsManifest.render(pages, components)
        val lines = text.trim().lines()
        assertEquals("bml-metrics\t1", lines[0])
        // Pages sort by object name; component "a"'s module equals the page's own and is excluded.
        assertEquals("P\tAboutPage\t/about\t-\t-\t-", lines[1])
        assertEquals("P\tHomePage\t/\tHomePage.js\ta,b\tSharedPage.js", lines[2])
    }

    @Test
    fun `whitespace-only styles do not reserve a production page stylesheet`() {
        val pages = listOf(BmlPageMeta("StylePage", "/a-b", null, listOf("blank")))
        val components = listOf(BmlComponentMeta("blank", "   ", deps = emptyList(), clientModule = null))

        assertEquals(
            "P\tStylePage\t/a-b\t-\t-\t-",
            BmlMetricsManifest.render(pages, components).trim().lines()[1],
        )
    }
}
