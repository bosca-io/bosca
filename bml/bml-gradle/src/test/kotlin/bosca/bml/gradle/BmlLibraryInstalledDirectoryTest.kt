package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Publishes browser dependencies installed beneath linked node_modules or scope directories. */
class BmlLibraryInstalledDirectoryTest {
    @Test
    fun `linked node_modules retains package ownership and tracks dependency changes`() = verify(false)

    @Test
    fun `linked scope retains package ownership and tracks dependency changes`() = verify(true)

    private fun verify(scope: Boolean) {
        val work = createTempDirectory("bml-library-installed-directory").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val producer = File(work, "producer").apply { mkdirs() }
            val installed = File(work, "installed-packages")
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            fun source(root: File, path: String, body: String) = File(root, path).apply { parentFile.mkdirs(); writeText(body) }
            source(installed, "@design/token/package.json", """{"name":"@design/token","main":"index.js"}""")
            val script = source(installed, "@design/token/index.js", "export const label = 'dependency-before';")
            val style = source(installed, "@design/token/theme.css", ".button { color: #123456; background: url('./icon.svg'); }")
            source(installed, "@design/token/icon.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>dependency-icon</text></svg>")
            val link = File(producer, if (scope) "node_modules/@design" else "node_modules").apply { parentFile.mkdirs() }.toPath()
            fun target(root: File) = if (scope) File(root, "@design") else root
            Files.createSymbolicLink(link, target(installed).toPath())
            source(producer, "settings.gradle", "rootProject.name = 'design'")
            source(producer, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            source(producer, "src/main/kotlin/Marker.kt", "object Marker")
            source(producer, "src/main/client/startup.ts", "import { label } from '@design/token'; globalThis.libraryLabel = label;")
            source(producer, "src/main/bml/button.bml", """
                <component tag="button">
                  <html:button/>
                  <style scoped>@import '@design/token/theme.css';</style>
                  <script client scoped>
                    import { label } from '@design/token'
                    ctx.root.setAttribute('data-label', label)
                  </script>
                </component>
            """.trimIndent())
            source(producer, "build.gradle", """
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
            """.trimIndent())
            fun publish() = GradleRunner.create().withProjectDir(producer).withPluginClasspath()
                .withArguments("publishMavenPublicationToFixtureRepository", "--configuration-cache", "--no-build-cache", "--stacktrace", "--max-workers=2").build()
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")
            fun text(jar: JarFile, path: String) = jar.getInputStream(requireNotNull(jar.getJarEntry(path))).reader().readText()
            fun inspect(label: String, color: String) {
                val consumer = File(work, "consumer").apply { deleteRecursively(); mkdirs() }
                JarFile(binary).use { jar ->
                    val metadata = JsonSlurper().parseText(text(jar, "META-INF/bml/client-manifest.json")) as Map<*, *>
                    val prefix = metadata["resourcePrefix"]
                    assertTrue(color in text(jar, "META-INF/bml/library-manifest.json"))
                    assertTrue(jar.entries().asSequence().any { it.name.endsWith(".svg") })
                    assertEquals(setOf("bml/button.bml", "client/startup.ts"),
                        (metadata["sources"] as List<*>).map { (it as Map<*, *>)["path"] }.toSet())
                    jar.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("$prefix/") }.forEach { entry ->
                        File(consumer, entry.name.removePrefix("$prefix/")).apply {
                            parentFile.mkdirs(); writeBytes(jar.getInputStream(entry).use { it.readBytes() })
                        }
                    }
                }
                JarFile(File(binary.parentFile, "design-1.0-sources.jar")).use { jar ->
                    assertTrue(jar.getJarEntry("Marker.kt") != null)
                    assertTrue(jar.getJarEntry("bml/button.bml") != null)
                    assertTrue(jar.getJarEntry("client/startup.ts") != null)
                    assertFalse(jar.entries().asSequence().any { it.name.endsWith("index.js") || "token/" in it.name || "installed-packages/" in it.name })
                }
                source(consumer, "runtime.ts", "export function defineComponent(tag, setup) { setup({root:{setAttribute:(key,value)=>globalThis.inlineLabel=value}}) }; export function mountAll() {}; export function defineIsland() {}; export function renderFragment() {};")
                val verify = source(consumer, "verify.mjs", """
                    import { bundleIslands } from ${groovy.json.JsonOutput.toJson(bundler.toURI().toString())};
                    import { readFileSync } from 'node:fs';
                    for (const [entry, output] of [['modules/ButtonPage.js', 'ButtonPage.js'], ['client/startup.js', 'startup.js']]) {
                        await bundleIslands({entryPoints:[new URL(entry, import.meta.url).pathname], outdir:new URL('./out', import.meta.url).pathname, runtime:new URL('./runtime.ts', import.meta.url).pathname});
                        new Function(readFileSync(new URL('./out/' + output, import.meta.url), 'utf8'))();
                    }
                    const expected = ${groovy.json.JsonOutput.toJson(label)};
                    if (globalThis.inlineLabel !== expected || globalThis.libraryLabel !== expected) throw new Error('Published dependency behavior was lost');
                """.trimIndent())
                val process = ProcessBuilder("node", verify.absolutePath).directory(consumer).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), output)
            }
            publish()
            inspect("dependency-before", "#123456")
            script.writeText("export const label = 'dependency-after';")
            val changedScript = publish()
            assertTrue("Reusing configuration cache" in changedScript.output, changedScript.output)
            assertEquals(TaskOutcome.SUCCESS, changedScript.task(":bmlBundleClient")?.outcome)
            inspect("dependency-after", "#123456")
            style.writeText(style.readText().replace("#123456", "#654321"))
            val changedStyle = publish()
            assertTrue("Reusing configuration cache" in changedStyle.output, changedStyle.output)
            assertEquals(TaskOutcome.SUCCESS, changedStyle.task(":bmlBundleLibraryStyles")?.outcome)
            inspect("dependency-after", "#654321")
            val replacement = File(work, "replacement-packages")
            installed.copyRecursively(replacement)
            source(replacement, "@design/token/index.js", "export const label = 'dependency-retargeted';")
            Files.delete(link)
            Files.createSymbolicLink(link, target(replacement).toPath())
            assertEquals(TaskOutcome.SUCCESS, publish().task(":bmlBundleClient")?.outcome)
            inspect("dependency-retargeted", "#654321")
            val stable = publish()
            assertTrue("Reusing configuration cache" in stable.output, stable.output)
            assertEquals(TaskOutcome.UP_TO_DATE, stable.task(":bmlBundleLibraryStyles")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, stable.task(":bmlBundleClient")?.outcome)
            producer.deleteRecursively()
            installed.deleteRecursively()
            replacement.deleteRecursively()
            inspect("dependency-retargeted", "#654321")
        } finally { work.deleteRecursively() }
    }
}
