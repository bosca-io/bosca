package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BmlBuildCacheTest {
    @Test
    fun `relocated cache restore retains compiler outputs and bundles fresh GraphQL operations`() {
        val work = createTempDirectory("bml-build-cache").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val project = File(work, "site").apply { mkdirs() }
            File(project, "settings.gradle").writeText("""
                rootProject.name = 'site'
                buildCache { local { directory = ${quote(File(work, "cache"))} } }
            """.trimIndent())
            File(project, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(project, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'io.bosca.graphql' }
                ${fixture.shared}
                dependencies { boscaGraphqlGenerator files(${fixture.runtime}) }
                // Only browser operations are needed by this fixture's client script.
                tasks.named('generateBoscaGraphqlClient') { enabled = false }
                bml {
                    generateKotlinSources.set(true)
                    clientBundler.set(file(${quote(File("../bml-runtime/tools/bundle.mjs").canonicalFile)}))
                    clientRuntime.set(file(${quote(File("../bml-runtime/src/index.ts").canonicalFile)}))
                }
            """.trimIndent())
            File(project, "src/main/bml/home.bml").apply {
                parentFile.mkdirs()
                writeText("""
                    <component tag="badge">
                      <style scoped>.badge { color: red; }</style>
                      <span class="badge">Cache test</span>
                    </component>
                    <page route="/"><html><body><badge/>
                      <script client>
                        import { Greeting } from "./graphql/Greeting"
                        console.log(Greeting.query)
                      </script>
                    </body></html></page>
                """.trimIndent())
            }
            File(project, "src/main/graphql/schema.graphqls").apply {
                parentFile.mkdirs()
                writeText("type Query { greeting: String!, salutation: String! }")
            }
            File(project, "src/main/graphql/Greeting.graphql").apply {
                writeText("query Greeting { greeting }")
            }
            val first = runner(project, "bmlBundleClient").build()
            assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, first.task(":bmlBundleClient")?.outcome)
            val generated = File(project, "build/generated/bml")
            val originals = listOf("compiler-ts", "metrics", "kotlin").associateWith { snapshot(File(generated, it)) }
            assertTrue(originals.getValue("compiler-ts").keys.any { it.endsWith(".ts") })
            assertTrue(originals.getValue("metrics").keys.any { it.endsWith(".css") })
            assertTrue(originals.getValue("kotlin").keys.any { it.endsWith(".kt.map") })
            assertFalse(File(generated, "compiler-ts/graphql").exists(), "Compiler output must not own GraphQL output")

            val relocated = File(work, "relocated-site").apply { mkdirs() }
            for (name in listOf("settings.gradle", "gradle.properties", "build.gradle", "src")) {
                check(File(project, name).copyRecursively(File(relocated, name)))
            }
            val restoredOutputs = File(relocated, "build/generated/bml")

            // The compiler outputs are portable. GraphQL remains owned by the separate generator.
            val operation = File(relocated, "src/main/graphql/Greeting.graphql")
            operation.writeText("query Greeting { salutation }")
            val restored = runner(relocated, "clean", "bmlBundleClient").build()
            assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileKotlin")?.outcome, restored.output)
            assertEquals(TaskOutcome.SUCCESS, restored.task(":bmlPrepareClient")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, restored.task(":bmlBundleClient")?.outcome)
            originals.forEach { (directory, expected) ->
                assertEquals(expected, snapshot(File(restoredOutputs, directory)), directory)
            }
            val graphql = File(restoredOutputs, "graphql/Greeting.ts").readText()
            assertTrue("salutation" in graphql)
            assertEquals(graphql, File(restoredOutputs, "ts/graphql/Greeting.ts").readText())
            assertTrue(File(restoredOutputs, "js-prod").listFiles().orEmpty().single { it.name.matches(Regex("""index\.page-[A-Z0-9]{8}\.js""")) }.readText().contains("salutation"))

            val reused = runner(relocated, "clean", "bmlBundleClient").build()
            assertTrue("Reusing configuration cache" in reused.output, reused.output)
            assertEquals(TaskOutcome.FROM_CACHE, reused.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, reused.task(":bmlBundleClient")?.outcome)

            val page = File(relocated, "src/main/bml/home.bml")
            page.writeText(page.readText().replace("Cache test", "Relocated source edit"))
            val edited = runner(relocated, "bmlBundleClient").build()
            assertEquals(TaskOutcome.SUCCESS, edited.task(":compileKotlin")?.outcome, edited.output)
            assertTrue(snapshot(File(restoredOutputs, "kotlin")).values.any { "Relocated source edit" in it })
        } finally {
            work.deleteRecursively()
        }
    }

    private fun runner(project: File, vararg tasks: String) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--build-cache", "--configuration-cache", "--no-watch-fs", "--stacktrace", "--max-workers=2")

    private fun snapshot(directory: File): Map<String, String> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readText() }
}
