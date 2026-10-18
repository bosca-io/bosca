package bosca.bml.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmlTransitiveLibraryConsumerTest {
    @Test
    fun `one Maven wrapper dependency compiles its transitive private renderers without either checkout`() {
        val work = createTempDirectory("bml-transitive-maven").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            fun project(name: String, version: String, exports: List<String>, dependencies: String = ""): File {
                val directory = File(work, "$name-$version").apply { mkdirs() }
                File(directory, "settings.gradle").writeText("rootProject.name = '$name'")
                File(directory, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
                File(directory, "build.gradle").writeText("""
                    plugins { id 'org.jetbrains.kotlin.jvm'; id 'java-library'; id 'io.bosca.bml'; id 'maven-publish' }
                    group = 'example'; version = '$version'
                    ${fixture.shared}
                    dependencies { $dependencies }
                    bml { packageName.set('example.$name.generated'); library { exports.set(${exports.joinToString(prefix = "[", postfix = "]") { "'$it'" }}) } }
                    publishing {
                        publications { maven(MavenPublication) { from components.java } }
                        repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } }
                    }
                """.trimIndent())
                return directory
            }
            fun source(project: File, name: String, text: String) = File(project, "src/main/bml/$name.bml").apply {
                parentFile.mkdirs(); writeText(text.trimIndent())
            }
            fun publishBase(version: String) {
                val base = project("base", version, listOf("button", "unused"))
                source(base, "button", """
                    <component tag="button"><prop name="label" type="String"/>
                      <html:button>{ label }<private-label/></html:button>
                    </component>
                """)
                source(base, "private-label", """<component tag="private-label"><span>BASE</span></component>""")
                source(base, "unused", """<component tag="unused"><span>UNUSED</span></component>""")
                runner(base, "publishMavenPublicationToFixtureRepository").build()
                assertTrue(base.deleteRecursively())
            }
            publishBase("1.0")
            val wrapper = project("wrapper", "1.0", listOf("card"), "api 'example:base:1.0'")
            source(wrapper, "imports", """<import library="example:base"/>""")
            source(wrapper, "card", """
                <component tag="card"><prop name="label" type="String"/>
                  <button :label="label"/><private-label/>
                </component>
            """)
            source(wrapper, "private-label", """<component tag="private-label"><span>WRAPPER</span></component>""")
            runner(wrapper, "publishMavenPublicationToFixtureRepository").build()
            val binary = File(fixture.repository, "example/wrapper/1.0/wrapper-1.0.jar")
            JarFile(binary).use { jar ->
                val metadata = jar.getInputStream(jar.getJarEntry("META-INF/bml/library-manifest.json")).use { JsonSlurper().parse(it) } as Map<*, *>
                val requirement = (metadata["requirements"] as List<*>).single() as Map<*, *>
                assertEquals("example:base", requirement["library"])
                assertEquals("1.0", requirement["version"])
                assertEquals(listOf("button"), requirement["components"])
                assertTrue((requirement["digest"] as String).startsWith("sha256:"))
            }
            assertTrue(wrapper.deleteRecursively())
            val consumer = File(work, "consumer").apply { mkdirs() }
            File(consumer, "settings.gradle").writeText("rootProject.name = 'consumer'")
            File(consumer, "gradle.properties").writeText("kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n")
            val build = File(consumer, "build.gradle").apply { writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml'; id 'application' }
                ${fixture.shared}
                dependencies { implementation 'example:wrapper:1.0' }
                bml { packageName.set('consumer.generated') }
                application { mainClass = 'consumer.MainKt' }
            """.trimIndent()) }
            source(consumer, "imports", """<import library="example:wrapper"/>""")
            source(consumer, "home", """<page route="/"><card label="Published"/><button/></page>""")
            source(consumer, "button", """<component tag="button"><html:button>LOCAL</html:button></component>""")
            File(consumer, "src/main/kotlin/consumer/Main.kt").apply {
                parentFile.mkdirs(); writeText("""
                    package consumer
                    fun main() = kotlinx.coroutines.runBlocking {
                        val ctx = bosca.bml.render.RenderContext()
                        consumer.generated.BmlPages.all.single().render(ctx)
                        println(ctx.toString())
                        check(consumer.generated.BmlComponents.all.size == 5)
                        check(consumer.generated.BmlComponents.renderers.size == 5)
                    }
                """.trimIndent())
            }
            val first = runner(consumer, "run").build()
            assertTrue("Published<span>BASE</span></button>" in first.output, first.output)
            assertTrue("WRAPPER</span>" in first.output && "LOCAL</button>" in first.output, first.output)
            val repeated = runner(consumer, "run").build()
            assertTrue("Reusing configuration cache" in repeated.output, repeated.output)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome)
            publishBase("2.0")
            build.appendText("\ndependencies { implementation 'example:base:2.0' }\n")
            val conflict = runner(consumer, "compileKotlin").buildAndFail()
            assertTrue("requires example:base:1.0, but resolved 2.0" in conflict.output, conflict.output)
            assertTrue("wrapper-1.0.jar" in conflict.output && "base-2.0.jar" in conflict.output, conflict.output)
            assertEquals(TaskOutcome.FAILED, conflict.task(":compileKotlin")?.outcome)
        } finally { work.deleteRecursively() }
    }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--stacktrace", "--configuration-cache", "--max-workers=2")
}
