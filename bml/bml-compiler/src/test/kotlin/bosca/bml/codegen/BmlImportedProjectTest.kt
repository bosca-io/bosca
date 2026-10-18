package bosca.bml.codegen

import bosca.bml.library.BmlImportFixtures
import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryManifest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BmlImportedProjectTest {
    @Test
    fun `a project import and local alias call compiled renderers without generating an imports page`() = fixture { root, source, binary ->
        source.resolve("imports.bml").writeText("""<import library="example:design"/>""")
        write(source, "nested/page.bml", """
            <import library="example:design" as="ui"/>
            <page route="/"><button disabled="false"/><ui:button disabled/><html:button>Native</html:button></page>
        """)
        val result = BmlProjectGenerator("consumer").generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(listOf("NestedPagePage.kt"), result.written.map { it.name })
        val generated = result.written.single().readText()
        assertEquals(2, "example.design.ButtonComponent.render".toRegex(RegexOption.LITERAL).findAll(generated).count())
        assertTrue("example.design.ButtonComponent.__bmlProp0(false)" in generated, generated)
        assertTrue("example.design.ButtonComponent.__bmlProp0(true)" in generated, generated)
        assertTrue("Native" in generated)
        assertFalse("ui:button>" in generated)
    }

    @Test
    fun `local components override unprefixed imports while an alias still selects the dependency`() = fixture { root, source, binary ->
        source.resolve("imports.bml").writeText("""<import library="example:design"/><import library="example:design" as="ui"/>""")
        write(source, "local.bml", """<component tag="button"><html:button>Local</html:button></component>""")
        write(source, "page.bml", """<page route="/"><button/><ui:button/></page>""")
        val result = BmlProjectGenerator("consumer").generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val page = result.written.single { it.name == "PagePage.kt" }.readText()
        assertTrue("ButtonComponent.render(ctx" in page)
        assertTrue("example.design.ButtonComponent.render(ctx" in page)
    }

    @Test
    fun `file aliases stay local and conflicting project imports fail with both sources`() = fixture { root, source, binary ->
        val other = binary(root, BmlImportFixtures.manifest("example:other", packageName = "example.other"), "other.jar")
        write(source, "a.bml", """<import library="example:design" as="ui"/><page route="/a"><ui:button/></page>""")
        write(source, "b.bml", """<import library="example:other" as="ui"/><page route="/b"><ui:button/></page>""")
        val generator = BmlProjectGenerator("consumer")
        val result = generator.generateAll(source, File(root, "out"), listOf(binary, other))
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertTrue("example.design.ButtonComponent.render" in result.written.single { it.name == "APage.kt" }.readText())
        assertTrue("example.other.ButtonComponent.render" in result.written.single { it.name == "BPage.kt" }.readText())
        source.resolve("imports.bml").writeText("""<import library="example:design" as="ui"/>""")
        val conflicting = generator.generateAll(source, File(root, "out"), listOf(binary, other))
        assertTrue(conflicting.written.isEmpty())
        assertTrue(conflicting.errors.any { "imports.bml" in it.message && "b.bml" in it.message }, conflicting.errors.toString())
    }

    @Test
    fun `private or unresolved exports and invalid global declarations fail generation`() = fixture { root, source, binary ->
        val global = source.resolve("imports.bml")
        global.writeText("""<import library="example:design"/>""")
        val page = write(source, "page.bml", """<page route="/"><private-label/></page>""")
        val generator = BmlProjectGenerator("consumer")
        var result = generator.generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.any { "private imported component" in it.message }, result.errors.toString())
        page.writeText("""<page route="/"><unknown:button/></page>""")
        result = generator.generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.any { "unknown:button" in it.message }, result.errors.toString())
        page.writeText("""<page route="/"><button/></page>""")
        global.writeText("""<page route="/invalid"/>""")
        result = generator.generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.any { "only imports and comments" in it.message }, result.errors.toString())
    }

    @Test
    fun `changed and removed project imports immediately change tag lookup`() = fixture { root, source, binary ->
        val global = source.resolve("imports.bml")
        global.writeText("""<import library="example:design"/>""")
        write(source, "page.bml", """<page route="/"><button>Save</button></page>""")
        val generator = BmlProjectGenerator("consumer")
        fun generate(): String {
            val result = generator.generateAll(source, File(root, "out"), listOf(binary))
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            return result.written.single().readText()
        }
        assertTrue("example.design.ButtonComponent.render" in generate())
        global.writeText("""<import library="example:design" as="ui"/>""")
        assertFalse("example.design.ButtonComponent.render" in generate())
        global.delete()
        assertFalse("example.design.ButtonComponent.render" in generate())
    }

    @Test
    fun `an explicitly designated imports file can live outside the source roots`() = fixture { root, source, binary ->
        val global = root.resolve("context.bml").apply { writeText("""<import library="example:design"/>""") }
        write(source, "page.bml", """<page route="/"><button/></page>""")
        val result = BmlProjectGenerator("consumer").generateAll(source, File(root, "out"), listOf(binary), global)
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(1, result.written.size)
        assertTrue("example.design.ButtonComponent.render" in result.written.single().readText())
    }

    @Test
    fun `dependency metadata changes invalidate render revisions without changing consumer markup`() = fixture { root, source, binary ->
        source.resolve("imports.bml").writeText("""<import library="example:design"/>""")
        write(source, "page.bml", """<page route="/"><button/></page>""")
        val generator = BmlProjectGenerator("consumer")
        val before = generator.generateAll(source, File(root, "out"), listOf(binary)).written.single().readText()
        binary(root, BmlImportFixtures.manifest(version = "2.0"))
        val after = generator.generateAll(source, File(root, "out"), listOf(binary)).written.single().readText()
        assertNotEquals(before.lineSequence().first { "renderRevision" in it }, after.lineSequence().first { "renderRevision" in it })
    }

    @Test
    fun `imported capabilities propagate through cross-file wrappers without leaking aliases`() = fixture { root, source, _ ->
        val deferred = BmlImportFixtures.component().copy(hasDeferredIslands = true)
        val binary = binary(root, BmlImportFixtures.manifest(components = listOf(deferred)))
        write(source, "wrapper.bml", """<import library="example:design" as="ui"/><component tag="wrapper"><ui:button/></component>""")
        write(source, "message.bml", """<message key="notice"><email><subject>Hi</subject><wrapper/></email></message>""")
        val result = BmlProjectGenerator("consumer").generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.any { "unavailable in <message>" in it.message }, result.errors.toString())
    }

    @Test
    fun `imported state cannot bypass shared shell validation through a local wrapper`() = fixture { root, source, _ ->
        val stateful = BmlImportFixtures.component().copy(hasServerState = true)
        val binary = binary(root, BmlImportFixtures.manifest(components = listOf(stateful)))
        write(source, "wrapper.bml", """<import library="example:design" as="ui"/><component tag="wrapper"><ui:button/></component>""")
        write(source, "page.bml", """<page route="/" cache="shared"><wrapper/></page>""")
        val result = BmlProjectGenerator("consumer").generateAll(source, File(root, "out"), listOf(binary))
        assertTrue(result.errors.any { "shared" in it.message && "session" in it.message.lowercase() }, result.errors.toString())
    }

    private fun write(source: File, path: String, content: String): File = source.resolve(path).apply {
        parentFile.mkdirs()
        writeText(content.trimIndent())
    }

    private fun binary(root: File, manifest: BmlLibraryManifest, name: String = "design.jar"): File =
        BmlImportFixtures.archive(File(root, name), mapOf(BmlLibraryArtifacts.MANIFEST_PATH to BmlLibraryArtifacts.encode(manifest)))

    private fun fixture(block: (File, File, File) -> Unit) {
        val root = createTempDirectory("bml-imported-project").toFile()
        try {
            val source = File(root, "markup").apply { mkdirs() }
            block(root, source, binary(root, BmlImportFixtures.manifest()))
        } finally {
            root.deleteRecursively()
        }
    }
}
