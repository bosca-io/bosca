package bosca.git.ci.parser

import bosca.git.model.PipelineTriggerType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Parses the workspace's own `.bosca/pipelines` definitions with the production parser. */
class WorkspacePipelinesTest {

    private val parser = PipelineYamlParser()

    /** Tests run from the `git/git-ci` project directory. */
    private val pipelines = File("../../.bosca/pipelines")

    private fun parse(name: String) = parser.parse(File(pipelines, name).readText(), ".bosca/pipelines/$name")

    @Test
    fun `every workspace pipeline parses`() {
        val files = pipelines.listFiles { file -> file.extension == "yaml" }.orEmpty()
        assertTrue(files.isNotEmpty(), "no pipelines found in ${pipelines.absolutePath}")
        files.forEach { file -> parser.parse(file.readText(), ".bosca/pipelines/${file.name}") }
    }

    @Test
    fun `image release prompts for the image and version`() {
        val definition = parse("release-image.yaml")
        val trigger = definition.triggers.single()
        assertEquals(PipelineTriggerType.RELEASE, trigger.type)
        val image = trigger.inputs.getValue("image")
        assertEquals("choice", image.type)
        assertNull(image.default, "the image must be chosen for every run")
        assertTrue("bosca-server" in image.options && "bosca-cli" in image.options)
        assertNull(trigger.inputs.getValue("version").default, "the version must be entered for every run")
        assertEquals(listOf("GITHUB_USERNAME", "GITHUB_TOKEN"), definition.jobs.getValue("publish-image").secrets)
    }

    @Test
    fun `CLI release prompts for the version and publishes after both platforms build`() {
        val definition = parse("release-cli.yaml")
        val trigger = definition.triggers.single()
        assertEquals(PipelineTriggerType.RELEASE, trigger.type)
        assertNull(trigger.inputs.getValue("version").default, "the version must be entered for every run")
        val publish = definition.jobs.getValue("publish")
        assertEquals(listOf("linux-x86_64", "macos-arm64"), publish.needs)
        assertEquals(listOf("GITHUB_TOKEN"), publish.secrets)
        assertEquals("macos", definition.jobs.getValue("macos-arm64").runner)
    }
}
