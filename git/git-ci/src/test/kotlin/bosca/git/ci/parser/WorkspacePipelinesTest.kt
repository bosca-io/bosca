package bosca.git.ci.parser

import bosca.git.model.PipelineTriggerType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        assertEquals(setOf(PipelineTriggerType.MANUAL, PipelineTriggerType.RELEASE), definition.triggers.map { it.type }.toSet())
        val trigger = definition.triggers.first { it.type == PipelineTriggerType.MANUAL }
        assertEquals(trigger.inputs, definition.triggers.first { it.type == PipelineTriggerType.RELEASE }.inputs)
        val image = trigger.inputs.getValue("image")
        assertEquals("choice", image.type)
        assertNull(image.default, "the image must be chosen for every run")
        assertTrue("bosca-server" in image.options && "bosca-cli" in image.options)
        assertNull(trigger.inputs.getValue("version").default, "the version must be entered for every run")
        assertEquals(listOf("GITHUB_USERNAME", "GITHUB_TOKEN"), definition.jobs.getValue("publish-image").secrets)
    }

    @Test
    fun `JVM release reports each failed dependency and retains publication verification`() {
        val definition = parse("release.yaml")
        assertEquals(setOf("test-jvm", "publish-jvm", "publish-firebase-scrypt", "notify"), definition.jobs.keys)
        val notify = definition.jobs.getValue("notify")
        assertEquals(setOf("publish-jvm", "publish-firebase-scrypt", "test-jvm"), notify.needs.toSet())
        assertEquals("always()", notify.condition)
        val artifacts = definition.jobs.getValue("publish-jvm").artifacts
        assertTrue(artifacts.all { it.type == "maven" && it.namespace == "bosca-maven" })
        val modules = listOf("core", "bml-annotations", "core-bml", "bml", "bml-compiler", "bml-server", "bml-message-host", "bml-message-client", "bml-gradle")
        assertEquals(
            modules.map { "io.bosca:$it:\${{ version }}" }.toSet() + "io.bosca.bml:io.bosca.bml.gradle.plugin:\${{ version }}",
            artifacts.map { it.coordinate }.toSet(),
        )
        assertFalse(definition.jobs.values.flatMap { it.steps }.any { it.uses == "git-push" || it.uses == "git-update-workspace-refs" })

        val expressions = PipelineExpressionParser()
        val success = assertNotNull(notify.steps.first { it.name == "Notify Success" }.condition)
        val failure = assertNotNull(notify.steps.first { it.name == "Notify Failure" }.condition)
        val results = notify.needs.associate { "needs.$it.result" to "success" }
        assertTrue(expressions.evaluateBoolean(success, ExpressionContext(extra = results)))
        assertFalse(expressions.evaluateBoolean(failure, ExpressionContext(extra = results)))
        for (dependency in notify.needs) {
            for (status in listOf("failure", "cancelled", "skipped")) {
                val context = ExpressionContext(extra = results + ("needs.$dependency.result" to status))
                assertFalse(expressions.evaluateBoolean(success, context), "$dependency=$status")
                assertTrue(expressions.evaluateBoolean(failure, context), "$dependency=$status")
            }
        }
    }

    @Test
    fun `CLI release prompts for the version and publishes after both platforms build`() {
        val definition = parse("release-cli.yaml")
        assertEquals(setOf(PipelineTriggerType.MANUAL, PipelineTriggerType.RELEASE), definition.triggers.map { it.type }.toSet())
        val trigger = definition.triggers.first { it.type == PipelineTriggerType.MANUAL }
        assertEquals(trigger.inputs, definition.triggers.first { it.type == PipelineTriggerType.RELEASE }.inputs)
        assertNull(trigger.inputs.getValue("version").default, "the version must be entered for every run")
        val publish = definition.jobs.getValue("publish")
        assertEquals(listOf("linux-x86_64", "macos-arm64"), publish.needs)
        assertEquals(listOf("GITHUB_TOKEN"), publish.secrets)
        assertEquals("macos", definition.jobs.getValue("macos-arm64").runner)
    }

    @Test
    fun `platform images publish directly from tags without upstream gates`() {
        val definition = parse("release-tag-images.yaml")
        assertEquals(PipelineTriggerType.TAG, definition.triggers.single().type)
        val job = definition.jobs.getValue("publish-images")
        val images = listOf("bosca-server", "bosca-runner", "bosca-studio", "git-server", "artifacts-server")
        assertEquals(images, job.matrix?.get("image"))
        assertTrue(job.needs.isEmpty() && job.requires.isEmpty() && job.pipelineRequires.isEmpty())
        val build = job.steps.first { it.name == "Build and push image" }
        for (image in images) {
            val context = ExpressionContext(ref = "refs/tags/v7.4.0", event = "tag", matrix = mapOf("image" to image))
            val expressions = PipelineExpressionParser()
            assertEquals(image, expressions.interpolate(build.env.getValue("IMAGE"), context))
            assertEquals("7.4.0", expressions.interpolate(build.env.getValue("VERSION"), context))
        }
    }

    @Test
    fun `BML browser runtime publishes with the tag version and gates web notifications`() {
        val definition = parse("release-web.yaml")
        assertEquals(PipelineTriggerType.TAG, definition.triggers.single().type)
        val runtime = definition.jobs.getValue("publish-bml-runtime")
        val artifact = runtime.artifacts.single()
        assertEquals("npm", artifact.type)
        assertEquals("@bosca", artifact.namespace)
        assertEquals("bml:\${{ version }}", artifact.coordinate)
        val version = runtime.steps.first { it.name == "Set BML Runtime Version" }
        assertEquals("99.0.0", PipelineExpressionParser().interpolate(
            version.env.getValue("RELEASE_VERSION"), ExpressionContext(ref = "refs/tags/v99.0.0", event = "tag"),
        ))
        val publishIndex = runtime.steps.indexOfFirst { it.name == "Publish BML Runtime" }
        assertTrue(runtime.steps.indexOfFirst { it.name == "Test BML Runtime" } in 0 until publishIndex)
        assertTrue(runtime.steps.indexOfFirst { it.name == "Build BML Runtime" } in 0 until publishIndex)
        assertEquals("bml/bml-runtime", runtime.steps[publishIndex].workingDirectory)
        assertTrue(runtime.steps[publishIndex].run.orEmpty().contains("BOSCA_REGISTRY_URL"))
        assertTrue(runtime.steps.any { it.uses == "setup-registry" })

        val notify = definition.jobs.getValue("notify")
        assertEquals(setOf("publish-web", "publish-bml-runtime"), notify.needs.toSet())
        assertEquals("always()", notify.condition)
        val expressions = PipelineExpressionParser()
        val success = assertNotNull(notify.steps.first { it.name == "Notify Success" }.condition)
        val failure = assertNotNull(notify.steps.first { it.name == "Notify Failure" }.condition)
        val results = notify.needs.associate { "needs.$it.result" to "success" }
        assertTrue(expressions.evaluateBoolean(success, ExpressionContext(extra = results)))
        for (dependency in notify.needs) {
            val context = ExpressionContext(extra = results + ("needs.$dependency.result" to "failure"))
            assertFalse(expressions.evaluateBoolean(success, context))
            assertTrue(expressions.evaluateBoolean(failure, context))
        }
    }

    @Test
    fun `message templates build from the workspace and upload the packaged project`() {
        val definition = parse("apps-bosca-messages-publish.yaml")
        assertEquals(setOf(PipelineTriggerType.TAG, PipelineTriggerType.MANUAL), definition.triggers.map { it.type }.toSet())
        val steps = definition.jobs.getValue("publish").steps
        val buildIndex = steps.indexOfFirst { it.name == "Build and test" }
        val build = steps[buildIndex]
        assertNull(build.workingDirectory)
        assertEquals("./gradlew --no-daemon :bosca-messages:build", build.run)
        assertTrue(steps.indexOfFirst { it.uses == "setup-node" } in 0 until buildIndex)
        assertTrue(steps.indexOfFirst { it.name == "Install BML Bundler Dependencies" } in 0 until buildIndex)
        val upload = steps.last()
        assertEquals("registry-upload", upload.uses)
        assertNull(upload.workingDirectory)
        assertEquals("bml-message", upload.with["namespace"])
        assertTrue(upload.with.getValue("files").contains("apps/bosca-messages/build/libs/bosca-messages.jar"))
        assertEquals("\${{ env.RELEASE_VERSION }}", upload.with["version"])
    }
}
