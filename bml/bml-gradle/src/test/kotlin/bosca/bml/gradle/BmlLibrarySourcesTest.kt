package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real Gradle source variant, archive contents, and source-input invalidation. */
class BmlLibrarySourcesTest {
    @Test
    fun `library sources archive includes original Kotlin and BML once and tracks changes`() {
        val project = createTempDirectory("bml library sources ").toFile()
        try {
            File(project, "settings.gradle").writeText("rootProject.name = 'design'")
            File(project, "build.gradle").writeText("""
                plugins {
                    id 'org.jetbrains.kotlin.jvm'
                    id 'io.bosca.bml'
                    id 'maven-publish'
                }
                group = 'example'
                version = '1.2.3'
                bml {
                    sourceDir.set(layout.projectDirectory.dir('markup'))
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                }
            """.trimIndent())
            val bml = File(project, "markup/components/button.bml").apply {
                parentFile.mkdirs()
                writeText("""<component tag="button"><html:button><slot/></html:button></component>""")
            }
            val imports = File(project, "markup/imports.bml").apply { writeText("{# module context #}") }
            val model = File(project, "src/main/kotlin/example/ButtonModel.kt").apply {
                parentFile.mkdirs(); writeText("package example\nclass ButtonModel(val label: String)")
            }
            for (path in listOf("markup/components/button.ts", "src/main/client/nested/start.ts", "public/theme.css")) {
                File(project, path).apply { parentFile.mkdirs(); writeText("/* authored source */") }
            }
            val dependencies = listOf("markup/components", "src/main/client/nested", "public/vendor").map { path ->
                File(project, "$path/node_modules/@design/token/internal-source.ts").apply {
                    parentFile.mkdirs(); writeText("export const internal = 'private-source';")
                }
            }
            val first = run(project)
            assertEquals(TaskOutcome.SUCCESS, first.task(":sourcesJar")?.outcome)
            assertEquals(null, first.task(":compileKotlin"))
            val archive = File(project, "build/libs/design-1.2.3-sources.jar")
            JarFile(archive).use { jar ->
                val entries = jar.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
                assertEquals(1, entries.count { it == "bml/components/button.bml" }, entries.toString())
                assertEquals(1, entries.count { it.endsWith("button.bml") }, entries.toString())
                assertTrue("bml/imports.bml" in entries)
                assertTrue("example/ButtonModel.kt" in entries)
                assertTrue(entries.containsAll(listOf("bml/components/button.ts", "client/nested/start.ts", "public/theme.css")))
                assertFalse(entries.any { "node_modules" in it.split('/') }, entries.toString())
                assertEquals(model.readText(), jar.getInputStream(jar.getJarEntry("example/ButtonModel.kt")).reader().readText())
                assertEquals(bml.readText(), jar.getInputStream(jar.getJarEntry("bml/components/button.bml")).reader().readText())
                assertFalse(entries.any { "generated" in it || "build-id" in it })
            }
            assertEquals(TaskOutcome.UP_TO_DATE, run(project).task(":sourcesJar")?.outcome)
            dependencies.forEach { it.appendText("\n// dependency-only edit") }
            assertEquals(TaskOutcome.UP_TO_DATE, run(project).task(":sourcesJar")?.outcome)
            bml.appendText("\n{# changed #}")
            imports.delete()
            assertEquals(TaskOutcome.SUCCESS, run(project).task(":sourcesJar")?.outcome)
            JarFile(archive).use { jar ->
                assertEquals(null, jar.getJarEntry("bml/imports.bml"))
                assertEquals(bml.readText(), jar.getInputStream(jar.getJarEntry("bml/components/button.bml")).reader().readText())
            }
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `library mode rejects an application composition root`() {
        val project = createTempDirectory("bml-library-application").toFile()
        try {
            File(project, "settings.gradle").writeText("rootProject.name = 'invalid-library'")
            File(project, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'application' }
                bml { library { exports.set(['button']) } }
            """.trimIndent())
            val result = GradleRunner.create().withProjectDir(project).withPluginClasspath()
                .withArguments("help", "--stacktrace").buildAndFail()
            assertTrue("BML component libraries cannot apply the application plugin" in result.output, result.output)
        } finally {
            project.deleteRecursively()
        }
    }

    private fun run(project: File) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments("sourcesJar", "--configuration-cache", "--stacktrace").build()
}
