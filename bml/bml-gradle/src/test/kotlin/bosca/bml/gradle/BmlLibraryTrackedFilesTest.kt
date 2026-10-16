package bosca.bml.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlLibraryTrackedFilesTest {
    private val project = createTempDirectory("bml-tracked-files").toFile().canonicalFile
    private val build = File(project, "build").apply { mkdirs() }
    private val sources = File(project, "src/main/bml").apply { mkdirs() }
    private val record = File(build, "record.json")
    private val stored = File(build, "tracking/stored.json")
    private val linkedPublic = File(project, "shared-public").apply { mkdirs() }
    private val public = File(project, "public").also { Files.createSymbolicLink(it.toPath(), linkedPublic.toPath()) }
    private val roots = listOf(sources, public)

    @AfterTest
    fun cleanUp() {
        project.deleteRecursively()
    }

    private fun file(path: String, text: String = path) = File(project, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun record(vararg files: File) = record.writeText(JsonOutput.toJson(files.map { it.path }))
    private fun isCurrent() = BmlLibraryTrackedFiles.isCurrent(stored, record, build, roots)
    private fun store() = BmlLibraryTrackedFiles.store(stored, record, build, roots)
    private fun storedPaths() = (JsonSlurper().parse(stored) as Map<*, *>).keys.map { it.toString() }.toSet()

    @Test
    fun `stored snapshot is current until a tracked file or its directory changes`() {
        val tokens = file("shared/tokens.css")
        record(tokens)
        store()
        assertTrue(isCurrent())

        tokens.writeText("changed")
        assertFalse(isCurrent(), "An edited tracked file must rerun the bundle")
        store()
        assertTrue(isCurrent())

        val sibling = file("shared/tokens.ts")
        assertFalse(isCurrent(), "A new sibling can change import resolution")
        store()
        sibling.delete()
        assertFalse(isCurrent(), "A removed sibling can change import resolution")
        store()

        tokens.delete()
        assertFalse(isCurrent(), "A deleted tracked file must rerun the bundle")
    }

    @Test
    fun `missing or damaged state always reruns the bundle`() {
        val tokens = file("shared/tokens.css")
        assertFalse(isCurrent(), "No record")
        store()
        assertFalse(stored.exists(), "Without a record nothing is stored")

        record(tokens)
        assertFalse(isCurrent(), "Record without a stored snapshot")
        store()
        stored.writeText("{ not json")
        assertFalse(isCurrent(), "Damaged stored snapshot")

        store()
        record.writeText("[ not json")
        assertFalse(isCurrent(), "Damaged record")
        store()
        assertFalse(stored.exists(), "A damaged record removes the stored snapshot")
    }

    @Test
    fun `declared input roots, installed packages, and build output are not re-hashed`() {
        val tokens = file("shared/tokens.css")
        val authored = file("src/main/bml/button.ts")
        val installed = file("node_modules/pkg/index.js")
        val generated = file("build/generated/Button.ts")
        // A linked root is recognized whether the bundler reports the authored or the real path.
        val logo = File(linkedPublic, "logo.svg").apply { writeText("logo") }
        record(tokens, authored, installed, generated, File(public, "logo.svg"), logo)
        store()
        assertEquals(setOf(tokens.path), storedPaths())

        for (untracked in listOf(authored, installed, generated, logo)) untracked.writeText("changed")
        assertTrue(isCurrent(), "Gradle tracks these files through ordinary task inputs")
    }
}
