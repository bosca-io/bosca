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

/** Verifies publication when only standalone assets require the library bundler. */
class BmlLibraryBundlerTest {
    @Test
    fun `standalone TypeScript requires fresh library output`() = verify(
        "src/main/client/startup.ts", "console.log('standalone-script');", "client/startup.js", "standalone-script",
    )

    @Test
    fun `standalone CSS requires fresh library output`() = verify(
        "src/main/client/theme.css", ".button { color: #123456; }", "styles/theme.css", "#123456",
    )

    @Test
    fun `public resources require fresh library output`() = verify(
        "public/icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>library-icon</text></svg>",
        "public/icon.svg", "library-icon",
    )

    private fun verify(source: String, content: String, output: String, expected: String) {
        val work = createTempDirectory("bml-library-bundler").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val project = File(work, "producer").apply { mkdirs() }
            fun write(path: String, text: String) = File(project, path).apply { parentFile.mkdirs(); writeText(text) }
            write("settings.gradle", "rootProject.name = 'design'")
            write("gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            write("src/main/kotlin/Marker.kt", "object Marker")
            write("src/main/bml/button.bml", "<component tag=\"button\"><html:button/></component>")
            write(source, content)
            // Models an older/custom bundler that accepts unknown arguments but emits no library output.
            val unsupported = File(work, "bundle.mjs").apply { writeText("process.exit(0);\n") }
            val current = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            fun configure(bundler: File, skipBundling: Boolean = false) = write("build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.0'
                ${fixture.shared}
                bml {
                    packageName.set('example.generated')
                    library { exports.set(['button']) }
                    clientBundler.set(file(${quote(bundler)}))
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
                ${if (skipBundling) "tasks.named('bmlBundleClient') { enabled = false }" else ""}
            """.trimIndent())
            fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
                .withArguments(*tasks, "--configuration-cache", "--no-build-cache", "--no-watch-fs", "--stacktrace", "--max-workers=2")
            val publish = "publishMavenPublicationToFixtureRepository"
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")

            configure(unsupported)
            val missing = runner(publish).buildAndFail()
            assertTrue("BML bundler did not produce library client assets" in missing.output, missing.output)
            assertEquals(TaskOutcome.FAILED, missing.task(":bmlBundleClient")?.outcome)
            assertEquals(null, missing.task(":$publish"))
            assertFalse(binary.exists())

            configure(current)
            runner(publish).build()
            JarFile(binary).use { jar ->
                assertTrue(jar.getJarEntry("META-INF/bml/client-manifest.json") != null)
                val entry = jar.entries().asSequence().single { it.name.endsWith("/$output") }
                assertTrue(expected in jar.getInputStream(entry).reader().readText())
            }
            val published = binary.readBytes()
            val reused = runner(publish).build()
            assertTrue("Reusing configuration cache" in reused.output, reused.output)
            assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":bmlBundleClient")?.outcome)

            // An earlier successful bundle must not conceal a tool that stops emitting library assets.
            configure(unsupported)
            val stale = runner(publish).buildAndFail()
            assertTrue("BML bundler did not produce library client assets" in stale.output, stale.output)
            assertEquals(null, stale.task(":$publish"))
            assertTrue(published.contentEquals(binary.readBytes()))

            // Archive validation also enforces the contract if the bundling task never executes.
            configure(current, skipBundling = true)
            val skipped = runner("clean", publish).buildAndFail()
            assertTrue("Binary archive is missing bundled library client assets" in skipped.output, skipped.output)
            assertEquals(TaskOutcome.SKIPPED, skipped.task(":bmlBundleClient")?.outcome)
            assertEquals(null, skipped.task(":$publish"))
            assertTrue(published.contentEquals(binary.readBytes()))
        } finally { work.deleteRecursively() }
    }
}
