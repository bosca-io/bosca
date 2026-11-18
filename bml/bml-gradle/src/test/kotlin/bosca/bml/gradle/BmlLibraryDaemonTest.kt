package bosca.bml.gradle

import bosca.bml.library.BmlLibraryArtifacts
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import kotlinx.coroutines.runBlocking
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Default daemon compilation must emit pure-BML modules without a Kotlin source to trigger it. */
class BmlLibraryDaemonTest {
    @Test
    fun `default daemon publishes and consumes BML-only modules after edits and component removal`() {
        val work = createTempDirectory("bml-default-daemon").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val bundler = File("../bml-runtime/tools/bundle.mjs").canonicalFile
            fun write(root: File, path: String, content: String) = File(root, path).apply {
                parentFile.mkdirs(); writeText(content.trimIndent())
            }
            fun project(name: String, library: Boolean): File {
                val root = File(work, name).apply { mkdirs() }
                write(root, "settings.gradle", "rootProject.name = '$name'")
                write(root, "gradle.properties", "org.gradle.jvmargs=-Xmx1g\nkotlin.daemon.jvmargs=-Xmx768m\n")
                write(root, "build.gradle", """
                    plugins { id 'org.jetbrains.kotlin.jvm'; id 'java-library'; id 'io.bosca.bml'; id 'maven-publish' }
                    group = 'example'; version = '1.0'
                    ${fixture.shared}
                    ${if (library) "" else "dependencies { implementation 'example:design:1.0' }"}
                    bml { packageName.set('example.$name.generated'); clientBundler.set(file(${quote(bundler)}))
                        ${if (library) "library { exports.set(['button']) }" else ""} }
                    publishing { publications { maven(MavenPublication) { from components.java } }
                        repositories { maven { name = 'fixture'; url = uri(${quote(fixture.repository)}) } } }
                """)
                return root
            }
            val producer = project("design", true)
            val button = write(producer, "src/main/bml/button.bml", """<component tag="button"><html:button>Before</html:button></component>""")
            val spare = write(producer, "src/main/bml/spare.bml", """<component tag="spare"><span>Private</span></component>""")
            val publish = "publishMavenPublicationToFixtureRepository"
            val first = runner(producer, publish).build()
            assertEquals(TaskOutcome.SUCCESS, first.task(":compileKotlin")?.outcome)
            val binary = File(fixture.repository, "example/design/1.0/design-1.0.jar")
            JarFile(binary).use { jar ->
                assertTrue(jar.getJarEntry("example/design/generated/ButtonComponent.class") != null)
                assertTrue(jar.getJarEntry("example/design/generated/SpareComponent.class") != null)
                assertTrue(jar.getJarEntry(BmlLibraryArtifacts.MANIFEST_PATH) != null)
            }
            val repeated = runner(producer, publish).build()
            assertTrue("Reusing configuration cache" in repeated.output, repeated.output)
            assertEquals(TaskOutcome.UP_TO_DATE, repeated.task(":compileKotlin")?.outcome)
            val consumer = project("consumer", false)
            write(consumer, "src/main/bml/imports.bml", """<import library="example:design"/>""")
            write(consumer, "src/main/bml/pages/home.bml", """<page route="/"><button/></page>""")
            runner(consumer, "jar").build()
            val consumerJar = File(consumer, "build/libs/consumer-1.0.jar")
            assertTrue("Before" in render(consumerJar, binary))
            button.writeText(button.readText().replace("Before", "After"))
            assertTrue(spare.delete())
            runner(producer, publish).build()
            JarFile(binary).use { jar ->
                assertEquals(null, jar.getJarEntry("example/design/generated/SpareComponent.class"))
                val manifest = BmlLibraryArtifacts.decode(jar.getInputStream(jar.getJarEntry(BmlLibraryArtifacts.MANIFEST_PATH)).reader().readText())
                assertEquals(listOf("button"), manifest.components.map { it.tag })
            }
            assertTrue(producer.deleteRecursively())
            val changed = runner(consumer, "jar", "--refresh-dependencies").build()
            assertEquals(TaskOutcome.SUCCESS, changed.task(":compileKotlin")?.outcome)
            val cached = runner(consumer, "jar").build()
            assertTrue("Reusing configuration cache" in cached.output, cached.output)
            assertEquals(TaskOutcome.UP_TO_DATE, cached.task(":compileKotlin")?.outcome)
            val application = File(work, "application.jar")
            consumerJar.copyTo(application)
            assertTrue(consumer.deleteRecursively())
            assertTrue("After" in render(application, binary))
        } finally { work.deleteRecursively() }
    }

    private fun render(application: File, library: File): String =
        URLClassLoader(arrayOf(application, library).map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader).use { loader ->
            val type = loader.loadClass("example.consumer.generated.PagesHomePage")
            val page = type.getField("INSTANCE").get(null) as BmlPageRenderer
            runBlocking { val context = RenderContext(); page.render(context); context.toString() }
        }

    private fun runner(project: File, vararg tasks: String): GradleRunner = GradleRunner.create()
        .withProjectDir(project).withPluginClasspath()
        .withArguments(*tasks, "--stacktrace", "--configuration-cache", "--max-workers=2")
}
