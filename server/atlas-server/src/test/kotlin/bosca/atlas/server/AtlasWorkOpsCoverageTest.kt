package bosca.atlas.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AtlasWorkOpsCoverageTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = kotlin.io.path.createTempDirectory("atlas-coverage-test-").toFile()
        val names = listOf("atlas.workops.coverage.agent", "atlas.workops.coverage.directory")
        val previous = names.associateWith(System::getProperty)
        try {
            names.forEach(System::clearProperty)
            block(directory)
        } finally {
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
            directory.deleteRecursively()
        }
    }

    @Test fun `ordinary fixtures do not enable coverage or write settings`() = fixture { directory ->
        assertTrue(atlasWorkOpsCoverageArguments(directory, "api").isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun `each instrumented process writes to a distinct report and retains the UI report scope`() = fixture { directory ->
        val agent = File(directory, "agent.jar").apply { writeText("test agent") }
        val reports = File(directory, "reports")
        System.setProperty("atlas.workops.coverage.agent", agent.absolutePath)
        System.setProperty("atlas.workops.coverage.directory", reports.absolutePath)
        val first = atlasWorkOpsCoverageArguments(directory, "ui")
        val second = atlasWorkOpsCoverageArguments(directory, "ui")
        assertNotEquals(first, second)
        assertTrue(first.single().startsWith("-javaagent:${agent.absolutePath}=file:"))
        val settings = directory.listFiles().orEmpty().filter { it.extension == "args" }.map(File::readText)
        assertEquals(2, settings.size)
        assertNotEquals(settings[0].lineSequence().first(), settings[1].lineSequence().first())
        for (value in settings) {
            assertTrue(value.startsWith("report.file=${reports.absolutePath}/"))
            assertEquals(listOf("include=bosca.workops.ui.*", "exclude=bosca.workops.ui.generated.*", "exclude=bosca.workops.ui.graphql.*"), value.lineSequence().drop(1).filter(String::isNotEmpty).toList())
        }
    }

    @Test fun `requested coverage requires an existing agent and explicit output directory`() = fixture { directory ->
        System.setProperty("atlas.workops.coverage.agent", File(directory, "missing.jar").absolutePath)
        assertFailsWith<IllegalArgumentException> { atlasWorkOpsCoverageArguments(directory, "api") }
        val agent = File(directory, "agent.jar").apply { writeText("test agent") }
        System.setProperty("atlas.workops.coverage.agent", agent.absolutePath)
        assertFailsWith<IllegalArgumentException> { atlasWorkOpsCoverageArguments(directory, "api") }
    }

    @Test fun `process teardown rejects missing empty and still locked coverage reports`() = fixture { directory ->
        atlasValidateWorkOpsCoverage(directory)
        val agent = File(directory, "agent.jar").apply { writeText("test agent") }
        System.setProperty("atlas.workops.coverage.agent", agent.absolutePath)
        System.setProperty("atlas.workops.coverage.directory", File(directory, "reports").absolutePath)
        atlasWorkOpsCoverageArguments(directory, "ui")
        val settings = directory.listFiles().orEmpty().single { it.extension == "args" }
        val report = File(settings.useLines { it.first().removePrefix("report.file=") })
        assertFailsWith<IllegalStateException> { atlasValidateWorkOpsCoverage(directory) }
        report.writeBytes(byteArrayOf())
        assertFailsWith<IllegalStateException> { atlasValidateWorkOpsCoverage(directory) }
        report.writeText("nonempty report")
        val lock = File(report.absolutePath + ".lck").apply { createNewFile() }
        assertFailsWith<IllegalStateException> { atlasValidateWorkOpsCoverage(directory) }
        check(lock.delete())
        atlasValidateWorkOpsCoverage(directory)
    }
}
