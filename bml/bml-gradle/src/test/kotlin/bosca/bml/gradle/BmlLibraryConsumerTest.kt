package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmlLibraryConsumerTest {
    @Test
    fun `a published library is imported by BML with tracked project imports and no producer checkout`() {
        val root = createTempDirectory("bml-library-consumer").toFile()
        try {
            val fixture = BmlCompilerFixture(root)
            val producer = File(root, "producer").apply { mkdirs() }
            File(producer, "settings.gradle").writeText("rootProject.name = 'design'")
            File(producer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(producer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'maven-publish' }
                group = 'example'; version = '1.0'
                ${fixture.shared}
                bml { packageName.set('example.design.generated'); library { exports.set(['button', 'badge']) } }
                publishing {
                    publications { maven(MavenPublication) { from components.java } }
                    repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                }
            """.trimIndent())
            File(producer, "src/main/bml/button.bml").apply {
                parentFile.mkdirs()
                writeText("""
                    <component tag="button">
                      <prop name="label" type="String" default="Default"/>
                      <html:button>{ label }<slot/></html:button>
                    </component>
                """.trimIndent())
            }
            File(producer, "src/main/bml/badge.bml").writeText("""<component tag="badge"><span>Unused</span></component>""")
            runner(producer, "publishMavenPublicationToFixtureRepository").build()
            assertTrue(producer.deleteRecursively())

            val consumer = File(root, "consumer").apply { mkdirs() }
            File(consumer, "settings.gradle").writeText("rootProject.name = 'consumer'")
            File(consumer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            File(consumer, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'application' }
                ${fixture.shared}
                dependencies { implementation 'example:design:1.0' }
                bml { sourceDir.set(layout.projectDirectory.dir('markup')); packageName.set('consumer.pages') }
                application { mainClass = 'consumer.MainKt' }
            """.trimIndent())
            val imports = File(consumer, "markup/imports.bml").apply {
                parentFile.mkdirs()
                writeText("""<import library="example:design"/>""")
            }
            val page = File(consumer, "markup/nested/home.bml").apply {
                parentFile.mkdirs()
                writeText("""<page route="/"><button label="Published"> library</button><html:button>Native</html:button></page>""")
            }
            File(consumer, "src/main/kotlin/consumer/Main.kt").apply {
                parentFile.mkdirs()
                writeText("""
                    package consumer
                    fun main() = kotlinx.coroutines.runBlocking {
                        val context = bosca.bml.render.RenderContext()
                        consumer.pages.BmlPages.all.single().render(context)
                        println(context.toString())
                    }
                """.trimIndent())
            }
            val first = runner(consumer, "run").build()
            assertTrue("<button>Published library</button><button>Native</button>" in first.output, first.output)
            val repeated = runner(consumer, "run").build()
            assertTrue("Reusing configuration cache" in repeated.output, repeated.output)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome)

            // Only the imports file changes: button now means native HTML rather than the component.
            imports.writeText("""<import library="example:design" as="ui"/>""")
            val aliased = runner(consumer, "run").build()
            assertEquals(TaskOutcome.SUCCESS, aliased.task(":compileKotlin")?.outcome)
            assertTrue("<button label=\"Published\"> library</button>" in aliased.output, aliased.output)
            page.writeText(page.readText().replace("<button label=", "<ui:button label=").replace("</button>", "</ui:button>"))
            val qualified = runner(consumer, "run").build()
            assertTrue("<button>Published library</button>" in qualified.output, qualified.output)

            // Change only export metadata, leaving source files and compiled class ABIs unchanged.
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")
            val original = binary.readBytes()
            val replacement = File(root, "changed.jar")
            JarFile(binary).use { jar ->
                JarOutputStream(replacement.outputStream()).use { output ->
                    jar.entries().asSequence().forEach { entry ->
                        output.putNextEntry(JarEntry(entry.name))
                        val bytes = jar.getInputStream(entry).use { it.readBytes() }
                        output.write(if (entry.name == "META-INF/bml/library-manifest.json") {
                            val text = bytes.toString(Charsets.UTF_8)
                            val changed = text.replace(Regex("""("tag": "button",\s*"exported": )true"""), "\$1false")
                            check(changed != text) { "Fixture must change the button export metadata" }
                            changed.toByteArray()
                        } else bytes)
                        output.closeEntry()
                    }
                }
            }
            replacement.copyTo(binary, overwrite = true)
            val removedExport = runner(consumer, "compileKotlin", "--refresh-dependencies").buildAndFail()
            assertTrue("Unknown or private imported component <ui:button>" in removedExport.output, removedExport.output)
            assertEquals(TaskOutcome.FAILED, removedExport.task(":compileKotlin")?.outcome)
            binary.writeBytes(original)
            imports.delete()
            val missing = runner(consumer, "compileKotlin").buildAndFail()
            assertTrue("Unknown or private imported component <ui:button>" in missing.output, missing.output)
            assertEquals(TaskOutcome.FAILED, missing.task(":compileKotlin")?.outcome)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace", "--configuration-cache")
}
