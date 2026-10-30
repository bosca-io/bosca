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

/** Exercises the real bundler when no prepared TypeScript remains. */
class BmlClientBundleTest {
    @Test
    fun `server only site packages global CSS without TypeScript`() {
        val work = createTempDirectory("bml-global-css").toFile()
        try {
            val project = project(work)
            page(project).writeText(serverPage)
            val css = File(project, "src/main/client/app.css").apply {
                parentFile.mkdirs()
                writeText("body { color: #123456; }\n")
            }
            val built = runner(project, "jar").build()
            assertEquals(TaskOutcome.SUCCESS, built.task(":bmlBundleClient")?.outcome)
            assertPackagedCss(project, "#123456")

            val reused = runner(project, "jar").build()
            assertTrue("Reusing configuration cache" in reused.output, reused.output)
            assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":bmlBundleClient")?.outcome)

            css.writeText("body { color: #654321; }\n")
            val changed = runner(project, "jar").build()
            assertTrue("Reusing configuration cache" in changed.output, changed.output)
            assertEquals(TaskOutcome.SUCCESS, changed.task(":bmlBundleClient")?.outcome)
            assertPackagedCss(project, "#654321")

            val clean = runner(project, "clean", "jar").build()
            assertEquals(TaskOutcome.SUCCESS, clean.task(":bmlBundleClient")?.outcome)
            assertPackagedCss(project, "#654321")
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `removing last client script clears bundles and adding it back rebuilds them`() {
        val work = createTempDirectory("bml-remove-client").toFile()
        try {
            val project = project(work)
            val page = page(project)
            page.writeText(clientPage("first-client"))
            val first = runner(project, "jar").build()
            assertEquals(TaskOutcome.SUCCESS, first.task(":bmlBundleClient")?.outcome)
            val generated = File(project, "build/generated/bml")
            val development = File(generated, "js/HomePage.js")
            val production = File(generated, "js-prod/index.page.js")
            assertTrue(development.readText().contains("first-client"))
            assertTrue(production.readText().contains("first-client"))

            page.writeText(serverPage)
            val removed = runner(project, "jar").build()
            assertTrue("Reusing configuration cache" in removed.output, removed.output)
            assertEquals(TaskOutcome.SUCCESS, removed.task(":bmlBundleClient")?.outcome)
            for (directory in listOf("ts", "js", "js-prod")) {
                assertFalse(File(generated, directory).walkTopDown().any { it.isFile },
                    "$directory must not retain files from the removed client script")
            }
            val unchanged = runner(project, "jar").build()
            assertTrue("Reusing configuration cache" in unchanged.output, unchanged.output)
            assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":bmlBundleClient")?.outcome)

            page.writeText(clientPage("replacement-client"))
            val restored = runner(project, "jar").build()
            assertTrue("Reusing configuration cache" in restored.output, restored.output)
            assertEquals(TaskOutcome.SUCCESS, restored.task(":bmlBundleClient")?.outcome)
            assertTrue(development.readText().contains("replacement-client"))
            assertTrue(production.readText().contains("replacement-client"))
            assertFalse(production.readText().contains("first-client"))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun project(work: File): File {
        val fixture = BmlCompilerFixture(work)
        return File(work, "site").apply {
            mkdirs()
            File(this, "settings.gradle").writeText("rootProject.name = 'site'")
            File(this, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(this, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                ${fixture.shared}
                bml {
                    clientBundler.set(file(${quote(File("../bml-runtime/tools/bundle.mjs").canonicalFile)}))
                    clientRuntime.set(file(${quote(File("../bml-runtime/src/index.ts").canonicalFile)}))
                }
            """.trimIndent())
            File(this, "src/main/kotlin/example/PageModel.kt").apply {
                parentFile.mkdirs()
                writeText("package example\nobject PageModel { const val title = \"Server rendered page\" }\n")
            }
        }
    }

    private fun page(project: File): File = File(project, "src/main/bml/home.bml").apply { parentFile.mkdirs() }

    private val serverPage = "<page route=\"/\"><html><body>{ example.PageModel.title }</body></html></page>"

    private fun clientPage(message: String): String = """
        <page route="/"><html><body>{ example.PageModel.title }
          <script client>console.log("$message")</script>
        </body></html></page>
    """.trimIndent()

    private fun assertPackagedCss(project: File, color: String) {
        val css = File(project, "build/generated/bml/client-assets/app.css").readText()
        assertTrue(color in css, css)
        JarFile(File(project, "build/libs/site.jar")).use { jar ->
            val entry = requireNotNull(jar.getJarEntry("app.css"))
            assertEquals(css, jar.getInputStream(entry).reader().use { it.readText() })
        }
    }

    private fun runner(project: File, vararg tasks: String) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--no-build-cache", "--no-watch-fs", "--stacktrace", "--max-workers=2")
}
