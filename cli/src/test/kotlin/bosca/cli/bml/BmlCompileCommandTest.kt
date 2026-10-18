package bosca.cli.bml

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.library.BmlLibraryComponent
import bosca.bml.library.BmlLibraryManifest
import bosca.bml.library.BmlLibrarySource
import com.github.ajalt.clikt.testing.test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlCompileCommandTest {
    @Test
    fun `compile accepts repeated binary libraries and resolves a custom source module import`() = fixture { root ->
        val source = File(root, "markup").apply { mkdirs() }
        File(source, "imports.bml").writeText("""
            <import library="example:design"/>
            <import library="example:other" as="other"/>
        """.trimIndent())
        File(source, "page.bml").writeText("""<page route="/"><button/><other:card/></page>""")
        val design = binary(root, "design", "button")
        val other = binary(root, "other", "card")
        val output = File(root, "generated")
        val result = BmlCompileCommand().test(listOf(
            root.path, "--source", source.path, "--out", output.path,
            "--library", design.path, "--library", other.path,
        ))
        assertEquals(0, result.statusCode, result.stderr)
        val generated = File(output, "PagePage.kt").readText()
        assertTrue("example.design.ButtonComponent.render" in generated, generated)
        assertTrue("example.other.CardComponent.render" in generated, generated)
        assertFalse(File(output, "ImportsPage.kt").exists())
    }

    @Test
    fun `an undeclared binary dependency is a source diagnostic and a failing exit status`() = fixture { root ->
        val source = File(root, "src/main/bml").apply { mkdirs() }
        File(source, "imports.bml").writeText("""<import library="example:missing"/>""")
        File(source, "page.bml").writeText("""<page route="/"><button/></page>""")
        val result = BmlCompileCommand().test(root.path)
        assertEquals(1, result.statusCode)
        assertTrue("example:missing" in result.stderr, result.stderr)
        assertTrue("imports.bml" in result.stderr, result.stderr)
    }

    private fun binary(root: File, library: String, tag: String): File {
        val component = BmlLibraryComponent(
            tag, true, "example.$library.${tag.replaceFirstChar(Char::uppercaseChar)}Component",
            BmlLibrarySource("bml/$tag.bml", 1, 1, "sha256:" + "a".repeat(64)),
            emptyList(), true, emptyList(), emptyList(), "", null,
            false, false, false, false, "revision",
        )
        val manifest = BmlLibraryManifest(
            BmlLibraryArtifacts.FORMAT_VERSION, "example:$library", "1.0", "example.$library", "test", listOf(component),
        )
        return File(root, "$library.jar").apply {
            JarOutputStream(outputStream()).use { jar ->
                jar.putNextEntry(JarEntry(BmlLibraryArtifacts.MANIFEST_PATH))
                jar.write(BmlLibraryArtifacts.encode(manifest).toByteArray())
                jar.closeEntry()
            }
        }
    }

    private fun fixture(block: (File) -> Unit) {
        val root = createTempDirectory("bml-compile-command").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
