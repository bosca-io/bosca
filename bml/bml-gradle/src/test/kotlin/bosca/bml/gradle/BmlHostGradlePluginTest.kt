package bosca.bml.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.time.Instant
import java.util.jar.JarFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Real application composition checks, without the compiler plugin or producer source trees. */
class BmlHostGradlePluginTest {
    @Test
    fun `host identities survive packaging composition and configuration cache without entering libraries`() {
        val project = createTempDirectory("bml-host-identity").toFile()
        try {
            File(project, "settings.gradle").writeText("rootProject.name = 'hosts'; include 'library', 'embedded', 'combined'")
            for (name in listOf("library", "embedded", "combined")) {
                val directory = File(project, name).apply { mkdirs() }
                File(directory, "build.gradle").writeText(if (name == "library") """
                    plugins { id 'java-library'; id 'io.bosca.bml.host' }
                """.trimIndent() else """
                    plugins {
                        ${if (name == "embedded") "id 'io.bosca.bml.host'; id 'application'" else "id 'application'; id 'io.bosca.bml.host'"}
                    }
                    application { mainClass = 'Main' }
                    dependencies { implementation project(':${if (name == "combined") "embedded" else "library"}') }
                """.trimIndent())
                if (name != "library") File(directory, "src/main/java/Main.java").apply {
                    parentFile.mkdirs()
                    writeText("""
                        public class Main {
                            public static void main(String[] args) throws Exception {
                                var resources = Main.class.getClassLoader().getResources("$BUILD_ID_RESOURCE");
                                while (resources.hasMoreElements()) {
                                    try (var stream = resources.nextElement().openStream()) {
                                        System.out.println("BUILD_ID=" + new String(stream.readAllBytes()).trim());
                                    }
                                }
                            }
                        }
                    """.trimIndent())
                }
            }
            fun run(vararg extra: String) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
                .withArguments(extra.toList() + listOf(":combined:run", ":combined:jar", ":embedded:jar", ":library:jar",
                    "--configuration-cache", "--build-cache", "--max-workers=2", "--stacktrace")).build()
            fun identity(name: String) = File(project, "$name/build/generated/bml/build-id/$BUILD_ID_RESOURCE").readText()
            val first = run()
            val combined = identity("combined")
            val embedded = identity("embedded")
            Instant.parse(combined)
            Instant.parse(embedded)
            assertNotEquals(combined, embedded)
            assertEquals(setOf(combined, embedded), Regex("BUILD_ID=(.+)").findAll(first.output).map { it.groupValues[1].trim() }.toSet())
            assertEquals(null, first.task(":library:bmlBuildId"))
            assertFalse(File(project, "library/build/generated/bml").exists())
            for (name in listOf("combined", "embedded", "library")) JarFile(File(project, "$name/build/libs/$name.jar")).use { jar ->
                val entry = jar.getJarEntry(BUILD_ID_RESOURCE)
                if (name == "library") assertEquals(null, entry)
                else assertEquals(identity(name), jar.getInputStream(entry).reader().use { it.readText() })
                assertFalse(jar.entries().asSequence().any { it.name.startsWith("bml/generated/") })
            }
            assertFalse(first.tasks.any { it.path.contains("bmlBundle") || it.path.contains("compileKotlin") })
            val second = run()
            assertTrue("Reusing configuration cache" in second.output, second.output)
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":combined:bmlBuildId")?.outcome)
            assertEquals(combined, identity("combined"))
            run("--rerun-tasks")
            assertEquals(combined, identity("combined"))
            assertEquals(embedded, identity("embedded"))
            val fresh = run(":combined:clean")
            assertEquals(TaskOutcome.SUCCESS, fresh.task(":combined:bmlBuildId")?.outcome)
            assertNotEquals(combined, identity("combined"))
            assertEquals(embedded, identity("embedded"))
        } finally {
            project.deleteRecursively()
        }
    }
}
