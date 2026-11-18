package bosca.bml.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BmlLibraryClientCacheTest {
    @Test
    fun `asset only binary changes invalidate compilation bundles packaged readers and metrics`() {
        val work = createTempDirectory("bml-client-cache").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            val runtime = File("../bml-runtime/src/index.ts").canonicalFile
            fun write(project: File, path: String, text: String) = File(project, path).apply { parentFile.mkdirs(); writeText(text) }
            val producer = File(work, "producer").apply { mkdirs() }
            write(producer, "settings.gradle", "rootProject.name = 'design'")
            write(producer, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            write(producer, "build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.0'
                ${fixture.shared}
                bml { packageName.set('example.design.generated'); library { exports.set(['card']) }; clientBundler.set(file(${quote(bundler)})) }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            write(producer, "src/main/bml/card.bml", """<component tag="card"><span>Card</span></component>""")
            write(producer, "src/main/client/theme.css", ":root { --accent: red; }")
            write(producer, "src/main/client/startup.ts", "console.log('BEFORE_ASSET_CHANGE');")
            runner(producer, "publishMavenPublicationToFixtureRepository").build()
            val binary = File(work, "design.jar")
            File(fixture.repository, "example/design/1.0/design-1.0.jar").copyTo(binary)
            assertTrue(producer.deleteRecursively())
            val consumer = File(work, "consumer").apply { mkdirs() }
            write(consumer, "settings.gradle", "rootProject.name = 'consumer'")
            write(consumer, "gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            write(consumer, "build.gradle", """
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                ${fixture.shared}
                dependencies { implementation files(${quote(binary)}) }
                bml { packageName.set('consumer.generated'); clientBundler.set(file(${quote(bundler)})); clientRuntime.set(file(${quote(runtime)})) }
            """.trimIndent())
            write(consumer, "src/main/bml/imports.bml", """<import library="example:design"/>""")
            write(consumer, "src/main/bml/home.bml", """<page route="/"><html><head></head><body><card/></body></html></page>""")
            runner(consumer, "jar", "bmlMetrics").build()
            val packaged = File(consumer, "build/libs/consumer.jar")
            val prefix = "bml/applications/consumer.generated/"
            fun index() = JarFile(packaged).use { jar -> jar.getInputStream(jar.getJarEntry(prefix + "client-manifest.json")).readBytes() }
            val before = index()
            val repeated = runner(consumer, "jar", "bmlMetrics").build()
            assertTrue("Reusing configuration cache" in repeated.output, repeated.output)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":bmlBundleClient")?.outcome)
            val entries = JarFile(binary).use { jar -> jar.entries().asSequence().associate { it.name to jar.getInputStream(it).readBytes() }.toMutableMap() }
            @Suppress("UNCHECKED_CAST") val metadata = JsonSlurper().parse(entries.getValue("META-INF/bml/client-manifest.json")) as MutableMap<String, Any?>
            val resourcePrefix = metadata.getValue("resourcePrefix") as String
            val script = (metadata.getValue("globalScripts") as List<*>).single() as String
            val replacement = entries.getValue("$resourcePrefix/$script").decodeToString().replace("BEFORE_ASSET_CHANGE", "AFTER_ASSET_CHANGE").encodeToByteArray()
            entries["$resourcePrefix/$script"] = replacement
            @Suppress("UNCHECKED_CAST") val files = metadata.getValue("files") as List<MutableMap<String, Any?>>
            files.single { it["path"] == script }["digest"] = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(replacement))
            entries["META-INF/bml/client-manifest.json"] = JsonOutput.toJson(metadata).encodeToByteArray()
            JarOutputStream(binary.outputStream()).use { jar ->
                entries.toSortedMap().forEach { (path, bytes) -> jar.putNextEntry(JarEntry(path)); jar.write(bytes); jar.closeEntry() }
            }
            val changed = runner(consumer, "jar", "bmlMetrics").build()
            assertEquals(TaskOutcome.SUCCESS, changed.task(":compileKotlin")?.outcome)
            assertEquals(TaskOutcome.SUCCESS, changed.task(":bmlBundleClient")?.outcome)
            assertNotEquals(before.toList(), index().toList())
            JarFile(packaged).use { jar ->
                val js = jar.getInputStream(jar.entries().asSequence().single { it.name.startsWith(prefix + "prod/index.page-") && it.name.endsWith(".js") }).reader().readText()
                assertTrue("AFTER_ASSET_CHANGE" in js && "BEFORE_ASSET_CHANGE" !in js, js)
            }
            @Suppress("UNCHECKED_CAST") val report = JsonSlurper().parse(File(consumer, "build/reports/bml/site-metrics.json")) as Map<String, Any?>
            assertEquals(emptyList<Any>(), report["missing"])
            val page = (report["pages"] as List<*>).single() as Map<*, *>
            assertTrue((page["css"] as List<*>).isNotEmpty(), "Library global CSS must be priced into the page: $report")
            val unsupported = File(work, "unsupported.mjs").apply { writeText("process.exit(0)") }
            File(consumer, "build.gradle").appendText("\nbml { clientBundler.set(file(${quote(unsupported)})) }\n")
            val failed = runner(consumer, "jar").buildAndFail()
            assertEquals(TaskOutcome.FAILED, failed.task(":bmlBundleClient")?.outcome)
            assertTrue("BML bundler did not produce application client assets" in failed.output, failed.output)
        } finally { work.deleteRecursively() }
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--stacktrace", "--configuration-cache", "--max-workers=2")
}
