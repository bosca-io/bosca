package bosca.bml.gradle

import org.gradle.api.plugins.JavaApplication
import org.gradle.api.GradleException
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Exec
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlGradlePluginTest {

    @Test
    fun `client dependency installation rejects unresolved npm configuration before starting npm`() {
        val projectDir = kotlin.io.path.createTempDirectory("bml-npm-configuration").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply(BmlGradlePlugin::class.java)
            File(projectDir, "package.json").writeText("{}")
            File(projectDir, ".npmrc").writeText("@bosca:registry=https://${'$'}{BOSCA_NPM_REGISTRY}/")
            val started = File(projectDir, "npm-started")
            val task = project.tasks.named("bmlInstallClientDependencies", Exec::class.java).get()
            task.executable = "/bin/sh"
            task.setArgs(listOf("-c", "printf started > npm-started"))
            task.setEnvironment(emptyMap<String, String>())

            val error = assertFailsWith<GradleException> { task.actions.forEach { it.execute(task) } }
            assertTrue(error.message.orEmpty().contains("BOSCA_NPM_REGISTRY"))
            assertFalse(started.exists(), "npm must not contact an unresolved placeholder host")

            task.setEnvironment(mapOf("BOSCA_NPM_REGISTRY" to "artifacts.example.test/npm"))
            task.actions.forEach { it.execute(task) }
            assertEquals("started", started.readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `npm configuration comments optional variables and escaped literals require no environment values`() {
        val projectDir = kotlin.io.path.createTempDirectory("bml-npm-optional-configuration").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply(BmlGradlePlugin::class.java)
            File(projectDir, ".npmrc").writeText("""
                # ${'$'}{COMMENT}
                ; ${'$'}{ANOTHER_COMMENT}
                optional=${'$'}{OPTIONAL?}
                literal=\${'$'}{LITERAL}
            """.trimIndent())
            val task = project.tasks.named("bmlInstallClientDependencies", Exec::class.java).get()
            task.executable = "/bin/sh"
            task.setArgs(listOf("-c", "printf started > npm-started"))
            task.setEnvironment(emptyMap<String, String>())
            task.actions.forEach { it.execute(task) }
            assertEquals("started", File(projectDir, "npm-started").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `bundler runs the configured executable and preserves paths with spaces`() {
        val projectDir = kotlin.io.path.createTempDirectory("bml-node-executable").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply(BmlGradlePlugin::class.java)
            val executable = File(projectDir, "node with spaces")
            executable.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
            assertTrue(executable.setExecutable(true))
            val bundler = File(projectDir, "bundle with spaces.mjs").apply { writeText("") }
            val extension = project.extensions.getByType(BmlExtension::class.java)
            extension.nodeExecutable.set(executable.absolutePath)
            extension.clientBundler.set(bundler)

            val task = project.tasks.named("bmlBundleClient", Exec::class.java).get()
            val output = ByteArrayOutputStream()
            task.standardOutput = output
            task.actions.forEach { it.execute(task) }

            val arguments = output.toString(Charsets.UTF_8).lineSequence().toList()
            assertEquals(bundler.absolutePath, arguments.first())
            assertEquals("--in", arguments[1])
            assertEquals(File(projectDir, "build/generated/bml/ts").canonicalPath, File(arguments[2]).canonicalPath)
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `compiler artifact uses the Gradle plugin publication version`() {
        val expectedVersion = System.getenv("PUBLISH_VERSION")?.removePrefix("v") ?: "0.0.1"

        assertEquals(expectedVersion, BmlGradlePlugin().getPluginArtifact().version)
    }

    @Test
    fun `application projects get a Gradle-owned development lifecycle`() {
        val projectDir = kotlin.io.path.createTempDirectory("bml-gradle-plugin").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply("application")
            project.pluginManager.apply(BmlGradlePlugin::class.java)
            project.extensions.getByType(JavaApplication::class.java).mainClass.set("example.MainKt")

            val run = project.tasks.named("run", JavaExec::class.java).get()
            val dev = project.tasks.named("bmlDev", BmlDevTask::class.java).get()

            assertEquals("bml", dev.group)
            assertTrue(dev.description.orEmpty().contains("in-process hot swap"))
            assertTrue(dev.reloadClasspath.files.isNotEmpty(), "main output is isolated for reload generations")
            assertTrue(
                dev.generationMarker.get().asFile.invariantSeparatorsPath
                    .endsWith("build/bml/dev/current-generation"),
            )
            assertEquals("true", run.systemProperties["bml.dev"])
            assertTrue(
                File(run.systemProperties.getValue("bml.clientDir").toString())
                    .invariantSeparatorsPath.endsWith("build/generated/bml/js"),
            )

            val dependencies = dev.taskDependencies.getDependencies(dev).map { it.name }.toSet()
            assertTrue("classes" in dependencies)
            assertTrue("bmlBundleClient" in dependencies)
            assertTrue("bmlBundleClient" in run.taskDependencies.getDependencies(run).map { it.name })
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `build id is written once and packaged as a main resource`() {
        val projectDir = kotlin.io.path.createTempDirectory("bml-gradle-build-id").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply(BmlGradlePlugin::class.java)
            project.pluginManager.apply("application")

            val task = project.tasks.named("bmlBuildId").get()
            val idFile = project.layout.buildDirectory.file("generated/bml/build-id/$BUILD_ID_RESOURCE").get().asFile
            task.actions.forEach { it.execute(task) }
            val first = idFile.readText()
            assertTrue(runCatching { java.time.Instant.parse(first) }.isSuccess, first)

            // An existing id is kept, so local builds stay stable until `clean`.
            task.actions.forEach { it.execute(task) }
            assertEquals(first, idFile.readText())

            val resourceDirs = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java)
                .getByName("main").resources.srcDirs
            assertTrue(resourceDirs.any { it == idFile.parentFile.parentFile.parentFile.parentFile }, resourceDirs.toString())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `a BML library project does not write a build id`() {
        val projectDir = kotlin.io.path.createTempDirectory("bml-gradle-library").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
            project.pluginManager.apply("org.jetbrains.kotlin.jvm")
            project.pluginManager.apply(BmlGradlePlugin::class.java)

            assertEquals(null, project.tasks.findByName("bmlBuildId"))
        } finally {
            projectDir.deleteRecursively()
        }
    }
}
