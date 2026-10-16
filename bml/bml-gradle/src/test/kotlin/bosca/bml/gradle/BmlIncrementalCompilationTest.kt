package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The default daemon strategy keeps Kotlin IC while regenerating BML output on every compilation. */
class BmlIncrementalCompilationTest {
    @Test
    fun `daemon incremental compilation regenerates pages after BML and Kotlin edits`() {
        val work = createTempDirectory("bml-incremental").toFile()
        try {
            val fixture = BmlCompilerFixture(work)
            val project = File(work, "site").apply { mkdirs() }
            File(project, "settings.gradle").writeText("rootProject.name = 'site'")
            File(project, "gradle.properties").writeText("kotlin.compiler.execution.strategy=daemon\norg.gradle.jvmargs=-Xmx1g\n")
            File(project, "build.gradle").writeText("""
                plugins { id 'org.jetbrains.kotlin.jvm'; id 'io.bosca.bml' }
                ${fixture.shared}
            """.trimIndent())
            val model = File(project, "src/main/kotlin/example/PageModel.kt").apply {
                parentFile.mkdirs()
                writeText("package example\nobject PageModel { const val title = \"title-alpha\" }\n")
            }
            val page = File(project, "src/main/bml/home.bml").apply {
                parentFile.mkdirs()
                writeText("<page route=\"/\"><html><body><p>marker-one</p>{ example.PageModel.title }</body></html></page>")
            }
            fun compile(): String = GradleRunner.create().withProjectDir(project).withPluginClasspath()
                .withArguments("compileKotlin", "--no-build-cache", "--info", "--stacktrace").build().output
            fun pageBytecode(): String {
                val pages = File(project, "build/classes/kotlin/main").walkTopDown()
                    .filter { it.isFile && it.name.startsWith("HomePage") }.toList()
                assertTrue(pages.isNotEmpty(), "The BML page must be compiled")
                assertTrue(File(project, "build/classes/kotlin/main/bml/generated/BmlPages.class").isFile)
                return pages.joinToString { String(it.readBytes(), Charsets.ISO_8859_1) }
            }

            compile()
            assertTrue("marker-one" in pageBytecode())

            page.writeText(page.readText().replace("marker-one", "marker-two"))
            compile()
            pageBytecode().let {
                assertTrue("marker-two" in it)
                assertFalse("marker-one" in it)
            }

            // A Kotlin-only edit takes the incremental path and must still recompile the inlined constant.
            model.writeText("package example\nobject PageModel { const val title = \"title-beta\"; val extra = 1 }\n")
            val incremental = compile()
            assertTrue("compilerMode=INCREMENTAL_COMPILER" in incremental, incremental)
            pageBytecode().let {
                assertTrue("title-beta" in it)
                assertFalse("title-alpha" in it)
                assertTrue("marker-two" in it)
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
