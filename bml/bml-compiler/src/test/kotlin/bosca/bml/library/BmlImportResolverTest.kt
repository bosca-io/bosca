package bosca.bml.library

import bosca.bml.parser.BmlParser
import bosca.bml.parser.CommentNode
import bosca.bml.parser.ElementNode
import bosca.bml.parser.Severity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BmlImportResolverTest {
    private val design = BmlLibraryDependency(File("design.jar"), BmlImportFixtures.manifest())
    private val other = BmlLibraryDependency(
        File("other.jar"), BmlImportFixtures.manifest("example:other", packageName = "example.other"),
    )
    private val resolver = BmlImportResolver(listOf(design, other))

    @Test
    fun `one unaliased project declaration exposes only exports to nested pages`() {
        val global = parse("""{# project context #}
            <import library="example:design"/>
        """, "custom-markup/imports.bml", true)
        for (source in listOf("pages/a.bml", "components/nested/field.bml")) {
            val local = parse("""<page route="/"><button disabled/><html:button>Native</html:button></page>""", source)
            val result = resolver.resolve(global, local)
            assertTrue(result.problems.isEmpty(), result.problems.toString())
            assertEquals(setOf("button"), result.bindings.keys)
            val binding = result.bindings.getValue("button")
            assertSame(design, binding.dependency)
            assertEquals(design.manifest.components.first(), binding.component)
            assertEquals("custom-markup/imports.bml", binding.declaration.source)
            assertFalse("private-label" in result.bindings)
            assertFalse("html:button" in result.bindings)
        }
    }

    @Test
    fun `aliased declarations do not change unqualified lookup or native namespaces`() {
        val imports = parse("""<import library="example:design" as="ui"/>""")
        val result = resolver.resolve(null, imports)
        assertEquals(setOf("ui:button"), result.bindings.keys)
        assertTrue(result.problems.isEmpty())
        assertFalse("button" in result.bindings)
        assertFalse("html:button" in result.bindings)
        assertFalse("svg:button" in result.bindings)
    }

    @Test
    fun `global and file bindings deduplicate but additional aliases remain available`() {
        val global = parse("""<import library="example:design"/>""", "imports.bml", true)
        val local = parse("""
            <import library="example:design"/>
            <import library="example:design" as="ui"/>
            <import library="example:design" as="ui"/>
        """, "page.bml")
        val result = resolver.resolve(global, local)
        assertTrue(result.problems.isEmpty())
        assertEquals(setOf("button", "ui:button"), result.bindings.keys)
        assertSame(result.bindings.getValue("button").component, result.bindings.getValue("ui:button").component)
        assertSame(result.bindings.getValue("button").dependency, result.bindings.getValue("ui:button").dependency)
    }

    @Test
    fun `a local component shadows bare imports while aliased exports stay accessible`() {
        val imports = parse("""
            <import library="example:design"/>
            <import library="example:design" as="ui"/>
        """)
        val result = resolver.resolve(null, imports, localComponents = setOf("button"))
        assertTrue(result.problems.isEmpty())
        assertEquals(setOf("ui:button"), result.bindings.keys)
    }

    @Test
    fun `unqualified collisions fail in either order even when a local component masks the name`() {
        for (libraries in listOf(listOf("design", "other"), listOf("other", "design"))) {
            val global = parse("""<import library="example:${libraries[0]}"/>""", "imports.bml", true)
            val local = parse("{# local #}\n<import library=\"example:${libraries[1]}\"/>", "pages/preferences.bml")
            val result = resolver.resolve(global, local, localComponents = setOf("button"))
            assertTrue(result.bindings.isEmpty())
            val problem = result.problems.single()
            assertEquals("pages/preferences.bml", problem.source)
            assertEquals(Severity.Error, problem.diagnostic.severity)
            assertEquals(2, problem.diagnostic.span.startLine)
            assertTrue("imports.bml:1:1" in problem.diagnostic.message)
            assertTrue("pages/preferences.bml:2:1" in problem.diagnostic.message)
            assertTrue("Conflicting BML component 'button'" in problem.diagnostic.message)
        }
    }

    @Test
    fun `alias collisions fail even for libraries with disjoint exports`() {
        val separate = BmlLibraryDependency(
            File("separate.jar"), BmlImportFixtures.manifest(
                "example:separate", packageName = "example.separate", components = listOf(BmlImportFixtures.component("card")),
            ),
        )
        val imports = parse("""
            <import library="example:design" as="ui"/>
            <import library="example:separate" as="ui"/>
        """)
        val result = BmlImportResolver(listOf(design, separate)).resolve(null, imports)
        assertTrue(result.bindings.isEmpty())
        assertTrue("Conflicting BML import alias 'ui'" in result.problems.single().diagnostic.message)
    }

    @Test
    fun `distinct aliases resolve component collisions explicitly`() {
        val result = resolver.resolve(null, parse("""
            <import library="example:design" as="ui"/>
            <import library="example:other" as="other"/>
        """))
        assertTrue(result.problems.isEmpty())
        assertEquals(setOf("ui:button", "other:button"), result.bindings.keys)
    }

    @Test
    fun `unresolved imports report the declaring source and do not expose partial bindings`() {
        val global = parse("""<import library="example:design"/>""", "imports.bml", true)
        val result = resolver.resolve(global, parse("""<import library="example:absent"/>""", "page.bml"))
        assertTrue(result.bindings.isEmpty())
        val problem = result.problems.single()
        assertEquals("page.bml", problem.source)
        assertTrue("example:absent" in problem.diagnostic.message)
        assertTrue("resolved dependency" in problem.diagnostic.message)
    }

    @Test
    fun `import declarations are removed without disturbing page markup or comments`() {
        val imports = parse("""
            {# context #}<import library="example:design"/>
            <page route="/"><button/><html:button/></page>
        """)
        assertTrue(imports.problems.isEmpty())
        assertTrue(imports.document.nodes.any { it is CommentNode })
        assertEquals(listOf("page"), imports.document.nodes.filterIsInstance<ElementNode>().map { it.name })
        assertEquals(1, imports.imports.size)
    }

    @Test
    fun `imports accept only literal known attributes and version-free coordinates`() {
        for (source in listOf(
            """<import/>""", """<import library/>""", """<import library="example:design:1"/>""",
            """<import :library="selected"/>""", """<import library="{ selected }"/>""",
            """<import library="example:design" as/>""", """<import library="example:design" :as="alias"/>""",
            """<import library="example:design" @click="model.action"/>""",
            """<import library="example:design" {...attributes}/>""",
            """<import library="example:design" export="private-label"/>""",
            """<import library="example:design" library="example:other"/>""",
            """<import library="example:design">body</import>""",
        )) {
            val imports = parse(source)
            assertTrue(imports.problems.isNotEmpty(), source)
            assertTrue(imports.imports.isEmpty(), source)
            assertTrue(resolver.resolve(null, imports).bindings.isEmpty(), source)
        }
    }

    @Test
    fun `native and translation namespaces cannot be claimed by an alias`() {
        for (alias in listOf("html", "svg", "t", "", "a:b", "a b", "1bad")) {
            val result = parse("""<import library="example:design" as="$alias"/>""")
            assertTrue(result.imports.isEmpty(), alias)
            assertTrue(result.problems.any { "alias" in it.diagnostic.message }, alias)
        }
    }

    @Test
    fun `module imports files accept only imports whitespace and comments`() {
        val valid = parse("""
            {# context #}<!-- context -->
            <import library="example:design"/>
        """, "imports.bml", true)
        assertTrue(valid.problems.isEmpty())
        for (source in listOf(
            "text", "{ selected }", "<page route=\"/\"/>",
            "<component tag=\"local\"/>", "<script server>val a = 1</script>",
            "<style>button { color: red; }</style>",
        )) {
            val parsed = parse(source, "imports.bml", true)
            assertTrue(parsed.problems.any { "only imports and comments" in it.diagnostic.message }, source)
            assertTrue(resolver.resolve(parsed, parse("<page route=\"/\"/>")).bindings.isEmpty())
        }
    }

    @Test
    fun `imports nested in elements loops or conditional branches fail`() {
        val declaration = """<import library="example:design"/>"""
        for (source in listOf(
            "<page route=\"/\">$declaration</page>",
            "<for value in listOf(1)>$declaration</for>",
            "<if (ready)>$declaration<else>$declaration</if>",
            "<import library=\"example:design\">$declaration</import>",
        )) {
            val parsed = parse(source)
            assertTrue(parsed.problems.any { "top-level" in it.diagnostic.message }, source)
        }
    }

    @Test
    fun `a different module or removing imports does not retain another source scope`() {
        val global = parse("""<import library="example:design"/>""", "imports.bml", true)
        val local = parse("<page route=\"/\"/>", "page.bml")
        assertEquals(setOf("button"), resolver.resolve(global, local).bindings.keys)
        assertTrue(resolver.resolve(null, local).bindings.isEmpty())
        assertTrue(resolver.resolve(parse("{# removed #}", "imports.bml", true), local).bindings.isEmpty())
    }

    private fun parse(source: String, name: String = "page.bml", importsOnly: Boolean = false): BmlImportResolver.ParsedImports {
        val parsed = BmlParser.parse(source.trimIndent())
        assertFalse(parsed.hasErrors, parsed.diagnostics.toString())
        return BmlImportResolver.parse(name, parsed.document, importsOnly)
    }
}
