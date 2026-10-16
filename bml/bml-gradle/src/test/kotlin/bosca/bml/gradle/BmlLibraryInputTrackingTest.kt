package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Library bundling tracks the project files it read, not every file in the project. */
class BmlLibraryInputTrackingTest {
    @Test
    fun `unrelated project edits leave bundling and validation up to date while imported files rebuild`() {
        val work = createTempDirectory("bml-library-tracking").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "design").apply { mkdirs() }
            fun source(path: String, body: String) = File(producer, path).apply { parentFile.mkdirs(); writeText(body) }
            source("settings.gradle", "rootProject.name = 'design'")
            source("gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            source("build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                group = 'example'
                version = '1.2.3'
                ${fixture.shared}
                bml {
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                    clientBundler.set(file(${quote(File("../bml-runtime/tools/bundle.mjs").canonicalFile)}))
                }
            """.trimIndent())
            source("src/main/kotlin/example/Marker.kt", "package example\nobject Marker")
            source("src/main/bml/button.bml", """
                <component tag="button">
                  <html:button class="button"><slot/></html:button>
                  <style scoped>@import '../../../shared/tokens.css';</style>
                  <script client scoped>
                    import { label } from '../../../shared/label'
                    ctx.root.setAttribute('data-label', label)
                  </script>
                </component>
            """.trimIndent())
            val tokens = source("shared/tokens.css", ".button { color: #111111; }")
            val label = source("shared/label.ts", "export const label = 'first-label'")
            val tasks = listOf(":bmlBundleLibraryStyles", ":bmlBundleClient", ":bmlValidateLibrary")
            fun outcomes(result: BuildResult) = tasks.associateWith { result.task(it)?.outcome }
            val upToDate = tasks.associateWith { TaskOutcome.UP_TO_DATE }

            assertEquals(tasks.associateWith { TaskOutcome.SUCCESS }, outcomes(jar(producer)))
            assertEquals(upToDate, outcomes(jar(producer)), "An unchanged build is up to date immediately")

            source("README.md", "# Design library")
            source(".idea/workspace.xml", "<project/>")
            source("src/test/kotlin/example/MarkerTest.kt", "package example\nclass MarkerTest")
            source("docs/notes/usage.md", "notes")
            assertEquals(upToDate, outcomes(jar(producer)), "Files no bundle read must not rerun bundling or validation")

            label.writeText("export const label = 'second-label'")
            val labelChanged = jar(producer)
            assertEquals(TaskOutcome.SUCCESS, labelChanged.task(":bmlBundleClient")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, labelChanged.task(":bmlValidateLibrary")?.outcome)
            assertTrue("second-label" in moduleJs(producer))

            tokens.writeText(".button { color: #222222; }")
            assertEquals(TaskOutcome.SUCCESS, jar(producer).task(":bmlBundleLibraryStyles")?.outcome)
            assertTrue("#222222" in entry(producer, "META-INF/bml/library-manifest.json"))

            // A new file beside an imported one can change how that import resolves.
            source("shared/label.tsx", "export const label = 'sibling-label'")
            val sibling = jar(producer)
            assertEquals(TaskOutcome.SUCCESS, sibling.task(":bmlBundleClient")?.outcome)
            assertTrue("sibling-label" in moduleJs(producer))

            assertEquals(upToDate, outcomes(jar(producer)))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun jar(project: File): BuildResult = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments("jar", "--configuration-cache", "--no-build-cache", "--stacktrace", "--max-workers=2").build()

    private fun entry(project: File, path: String): String = JarFile(File(project, "build/libs/design-1.2.3.jar")).use { jar ->
        jar.getInputStream(requireNotNull(jar.getJarEntry(path)) { path }).bufferedReader().use { it.readText() }
    }

    private fun moduleJs(project: File): String {
        val manifest = JsonSlurper().parseText(entry(project, "META-INF/bml/client-manifest.json")) as Map<*, *>
        val module = (manifest["modules"] as Map<*, *>)["ButtonPage.js"] as Map<*, *>
        return entry(project, "${manifest["resourcePrefix"]}/${module["path"]}")
    }
}
