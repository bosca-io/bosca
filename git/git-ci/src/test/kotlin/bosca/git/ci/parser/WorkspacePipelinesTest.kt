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
    fun `each supported image has exactly one dedicated release pipeline`() {
        val script = File("../../scripts/release/build-image.sh").readText()
        val targets = assertNotNull(Regex("IMAGES=\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL).find(script))
            .groupValues[1].trim().split(Regex("\\s+")).toSet()
        val files = pipelines.listFiles { file -> file.name.startsWith("release-image-") && file.extension == "yaml" }.orEmpty()
        assertEquals(targets.map { "release-image-$it.yaml" }.toSet(), files.map { it.name }.toSet())
        assertFalse(File(pipelines, "release-image.yaml").exists())
        assertFalse(File(pipelines, "release-tag-images.yaml").exists())
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
    fun `CLI release publishes both platforms and checksums to Bosca`() {
        val definition = parse("release-cli.yaml")
        assertEquals(setOf(PipelineTriggerType.MANUAL, PipelineTriggerType.RELEASE), definition.triggers.map { it.type }.toSet())
        assertTrue(definition.triggers.all { it.inputs.isEmpty() })
        val source = File(pipelines, "release-cli.yaml").readText()
        val yaml = org.yaml.snakeyaml.Yaml().load<Map<Any, Any?>>(source)
        assertEquals(true, ((yaml["on"] ?: yaml[true]) as Map<*, *>)["manual"])
        val publish = definition.jobs.getValue("publish")
        assertEquals(listOf("linux-x86_64", "macos-arm64"), publish.needs)
        assertTrue(definition.secrets.isEmpty())
        assertTrue(publish.secrets.isEmpty())
        val artifact = publish.artifacts.single()
        assertEquals("raw", artifact.type)
        assertEquals("bosca", artifact.namespace)
        val expressions = PipelineExpressionParser()
        for (event in listOf("manual", "release")) {
            for (tag in listOf("7.4.0", "v7.4.0")) {
                val context = ExpressionContext(ref = "refs/tags/$tag", event = event)
                assertEquals("bosca-cli:7.4.0", expressions.interpolate(artifact.coordinate, context))
                for (platform in listOf("linux-x86_64", "macos-arm64")) {
                    val job = definition.jobs.getValue(platform)
                    val guard = job.steps.first()
                    assertEquals("Validate release tag", guard.name)
                    assertEquals("refs/tags/$tag", expressions.interpolate(guard.env.getValue("RELEASE_REF"), context))
                    for (step in job.steps.filter { "RELEASE_VERSION" in it.env }) {
                        assertEquals("7.4.0", expressions.interpolate(step.env.getValue("RELEASE_VERSION"), context))
                    }
                }
                assertEquals("7.4.0", expressions.interpolate(publish.steps.last().with.getValue("version"), context))
            }
        }
        val boscaIndex = publish.steps.indexOfFirst { it.uses == "registry-upload" }
        assertEquals(publish.steps.lastIndex, boscaIndex)
        assertTrue(publish.steps.indexOfFirst { it.name == "Generate checksums" } in 0 until boscaIndex)
        val bosca = publish.steps[boscaIndex]
        assertNull(bosca.condition, "Bosca publication is always required")
        assertEquals("bosca", bosca.with["namespace"])
        assertEquals("bosca-cli", bosca.with["name"])
        assertTrue(bosca.with.getValue("files").contains("linux-x86_64.tar.gz"))
        assertTrue(bosca.with.getValue("files").contains("macos-arm64.pkg"))
        assertTrue(bosca.with.getValue("files").contains("SHA256SUMS"))
        assertEquals("macos", definition.jobs.getValue("macos-arm64").runner)
    }

    @Test
    fun `CLI native build jobs configure root Gradle properties after checkout and before building`() {
        val definition = parse("release-cli.yaml")
        assertEquals(listOf("CLI_GRADLE_PROPERTIES"), definition.jobs.getValue("linux-x86_64").secrets)
        assertEquals(listOf("CLI_GRADLE_PROPERTIES", "MACOS_KEYCHAIN_PASSWORD"), definition.jobs.getValue("macos-arm64").secrets)
        for (platform in listOf("linux-x86_64", "macos-arm64")) {
            val steps = definition.jobs.getValue(platform).steps
            val configureIndex = steps.indexOfFirst { it.name == "Configure Gradle" }
            val checkoutIndex = steps.indexOfFirst { it.uses == "checkout" }
            val gradleIndex = steps.indexOfFirst { it.run.orEmpty().contains("./gradlew") }
            assertTrue(configureIndex in (checkoutIndex + 1) until gradleIndex, platform)
            val configure = steps[configureIndex]
            assertNull(configure.workingDirectory)
            assertEquals("\${{ secrets.CLI_GRADLE_PROPERTIES }}", configure.env["CLI_GRADLE_PROPERTIES"])
        }
    }

    @Test
    fun `release pipelines leave external artifact forwarding to the artifacts server`() {
        for (file in pipelines.listFiles { file -> file.extension == "yaml" }.orEmpty()) {
            val source = file.readText()
            for (reference in listOf("GITHUB_TOKEN", "GITHUB_USERNAME", "ghcr.io", "api.github.com", "publish-cli.sh")) {
                assertFalse(source.contains(reference), "${file.name} must not publish directly to GitHub: $reference")
            }
        }
    }

    @Test
    fun `image releases run manually from selected tags with independent jobs and versions`() {
        val files = pipelines.listFiles { file -> file.name.startsWith("release-image-") && file.extension == "yaml" }.orEmpty()
        assertTrue(files.isNotEmpty())
        val expressions = PipelineExpressionParser()
        for (file in files) {
            val image = file.name.removePrefix("release-image-").removeSuffix(".yaml")
            val definition = parse(file.name)
            assertEquals(setOf(PipelineTriggerType.MANUAL, PipelineTriggerType.RELEASE), definition.triggers.map { it.type }.toSet())
            assertTrue(definition.triggers.all { it.inputs.isEmpty() })
            assertEquals(setOf("publish-image", "notify"), definition.jobs.keys)
            val job = definition.jobs.getValue("publish-image")
            assertNull(job.matrix)
            assertTrue(job.needs.isEmpty() && job.requires.isEmpty() && job.pipelineRequires.isEmpty())
            assertTrue(job.steps.any { it.uses == "setup-registry" })
            assertTrue(job.secrets.isEmpty())
            val build = job.steps.first { it.name == "Build and push image" }
            val artifact = job.artifacts.single()
            assertEquals("docker", artifact.type)
            assertEquals("bosca", artifact.namespace)
            assertEquals("Validate release tag", job.steps.first().name)
            for (event in listOf("manual", "release")) {
                for (tag in listOf("7.4.0", "v7.4.0")) {
                    val context = ExpressionContext(ref = "refs/tags/$tag", event = event)
                    assertEquals(image, expressions.interpolate(build.env.getValue("IMAGE"), context))
                    assertEquals("7.4.0", expressions.interpolate(build.env.getValue("VERSION"), context))
                    assertEquals("$image:7.4.0", expressions.interpolate(artifact.coordinate, context))
                }
            }
            val notify = definition.jobs.getValue("notify")
            assertEquals(listOf("publish-image"), notify.needs)
            assertEquals("always()", notify.condition)
            val success = assertNotNull(notify.steps.first { it.name == "Notify Success" }.condition)
            val failure = assertNotNull(notify.steps.first { it.name == "Notify Failure" }.condition)
            for (status in listOf("success", "failure", "cancelled", "skipped")) {
                val context = ExpressionContext(extra = mapOf("needs.publish-image.result" to status))
                assertEquals(status == "success", expressions.evaluateBoolean(success, context))
                assertEquals(status != "success", expressions.evaluateBoolean(failure, context))
            }
        }
    }

    @Test
    fun `web release installs Chromium before browser tests and authenticates each npm publisher`() {
        val jobs = parse("release-web.yaml").jobs
        val web = jobs.getValue("publish-web")
        val install = web.steps.indexOfFirst { it.name == "Install Test Browser" }
        val test = web.steps.indexOfFirst { it.name == "Run Web Tests" }
        assertTrue(install in 0 until test)
        assertTrue(web.steps[install].run.orEmpty().contains("playwright install --with-deps chromium"))
        assertEquals("7.4.0", PipelineExpressionParser().interpolate(
            web.steps.first { it.name == "Extract Version" }.env.getValue("PUBLISH_VERSION"),
            ExpressionContext(ref = "refs/tags/v7.4.0", event = "tag"),
        ))
        for (job in listOf(web, jobs.getValue("publish-bml-runtime"))) {
            val publish = job.steps.last().run.orEmpty()
            assertTrue(publish.contains("npm config set"))
            assertTrue(publish.contains("BOSCA_REGISTRY_TOKEN"))
            assertTrue(publish.contains("/npm/"))
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
