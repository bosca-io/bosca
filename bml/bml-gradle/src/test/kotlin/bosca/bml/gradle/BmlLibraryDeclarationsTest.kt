package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises declaration publication, input tracking, binary extraction and real TypeScript checking. */
class BmlLibraryDeclarationsTest {
    @Test
    fun `stable type imports work after deleting producer and source attachment`() {
        val root = createTempDirectory("bml-library-type-consumer").toFile()
        try {
            val fixture = BmlCompilerFixture(root)
            val producer = File(root, "producer").apply { mkdirs() }
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            val runtime = File("../bml-runtime/src/index.ts").canonicalFile
            val tsc = File("../bml-runtime/node_modules/typescript/bin/tsc").canonicalFile
            fun write(project: File, path: String, text: String) = File(project, path).apply { parentFile.mkdirs(); writeText(text) }
            fun settings(project: File, name: String) {
                write(project, "settings.gradle", "rootProject.name = '$name'")
                write(project, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            }
            settings(producer, "design")
            write(producer, "build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.0'
                ${fixture.shared}
                bml {
                    packageName.set('example.design.generated')
                    library { exports.set(['button']) }
                    clientBundler.set(file(${quote(bundler)}))
                }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            write(producer, "src/main/kotlin/Marker.kt", "object Marker")
            write(producer, "src/main/bml/button.bml", """<component tag="button"><html:button><slot/></html:button></component>""")
            val declaration = write(producer, "src/main/client/contracts/select.d.ts", """
                declare module "@example/design/select" { export interface Selection { value: string } }
            """.trimIndent())
            val publish = "publishMavenPublicationToFixtureRepository"
            runner(producer, publish).build()
            val reused = runner(producer, publish).build()
            assertTrue("Reusing configuration cache" in reused.output, reused.output)
            assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":bmlBundleClient")?.outcome)
            declaration.writeText(declaration.readText().replace("value: string", "value: string; label: string"))
            val changed = runner(producer, publish).build()
            assertEquals(TaskOutcome.SUCCESS, changed.task(":bmlBundleClient")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, changed.task(":bmlValidateLibrary")?.outcome)
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")
            val sources = File(binary.parentFile, "design-1.0-sources.jar")
            JarFile(binary).use { jar ->
                val metadata = JsonSlurper().parse(jar.getInputStream(jar.getJarEntry("META-INF/bml/client-manifest.json"))) as Map<*, *>
                assertEquals(emptyList<Any>(), metadata["globalScripts"])
                assertEquals(emptyMap<Any, Any>(), metadata["modules"])
                val entry = jar.getJarEntry("${metadata["resourcePrefix"]}/client/contracts/select.d.ts")
                assertEquals(declaration.readText(), jar.getInputStream(entry).reader().readText())
            }
            JarFile(sources).use { jar ->
                assertEquals(declaration.readText(), jar.getInputStream(jar.getJarEntry("client/contracts/select.d.ts")).reader().readText())
            }
            assertTrue(producer.deleteRecursively())
            assertTrue(sources.delete())

            val consumer = File(root, "consumer").apply { mkdirs() }
            settings(consumer, "consumer")
            write(consumer, "build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                ${fixture.shared}
                dependencies { implementation 'example:design:1.0' }
                bml { packageName.set('consumer.pages'); clientBundler.set(file(${quote(bundler)})); clientRuntime.set(file(${quote(runtime)})) }
            """.trimIndent())
            write(consumer, "src/main/bml/imports.bml", """<import library="example:design"/>""")
            write(consumer, "src/main/bml/home.bml", """
                <page route="/">
                  <button>Published</button>
                  <script client>
                    import type { Selection } from "@example/design/select";
                    const selection: Selection = { value: "chosen", label: "Chosen" };
                    document.documentElement.dataset.selection = selection.value;
                  </script>
                </page>
            """.trimIndent())
            runner(consumer, "bmlBundleClient").build()
            val ts = File(consumer, "build/generated/bml/ts")
            assertEquals(1, ts.walkTopDown().count { it.isFile && it.name == "select.d.ts" })
            assertFalse(File(consumer, "build/generated/bml/resources").walkTopDown().any { it.name.endsWith(".d.ts") })
            for (mode in listOf("js", "js-prod")) {
                val files = File(consumer, "build/generated/bml/$mode").walkTopDown().filter { it.extension == "js" }.toList()
                assertTrue(files.isNotEmpty())
                assertTrue(files.any { "chosen" in it.readText() })
                assertFalse(files.any { "@example/design/select" in it.readText() })
            }
            write(consumer, "tsconfig.json", """
                {"compilerOptions":{"target":"ES2022","module":"ESNext","moduleResolution":"Bundler",
                "strict":true,"verbatimModuleSyntax":true,"noEmit":true,
                "paths":{"@bosca/bml":[${jsonString(runtime.path)}]}},"include":["build/generated/bml/ts/**/*.ts","contract.ts"]}
            """.trimIndent())
            val contract = write(consumer, "contract.ts", """
                import type { Selection } from "@example/design/select";
                export const selected: Selection = { value: "one", label: "One" };
            """.trimIndent())
            fun typecheck(): Pair<Int, String> {
                val process = ProcessBuilder("node", tsc.path, "--project", "tsconfig.json")
                    .directory(consumer).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                return process.waitFor() to output
            }
            val valid = typecheck()
            assertEquals(0, valid.first, valid.second)
            contract.writeText(contract.readText().replace(", label: \"One\"", ""))
            val invalid = typecheck()
            assertTrue(invalid.first != 0 && "label" in invalid.second, invalid.second)
        } finally { root.deleteRecursively() }
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--configuration-cache", "--no-build-cache", "--max-workers=2", "--stacktrace")

    private fun jsonString(value: String): String = groovy.json.JsonOutput.toJson(value)
}
