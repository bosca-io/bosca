package bosca.design.example

import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTestSetupTest {
    @Test
    fun `owned browsers isolate state and exit after blocked renderer and process checks`() {
        val original = File(System.getProperty("design.fixture.project"))
        val script = File(original, "../../../gradle/bml-test-browser.test.mjs").canonicalFile
        val log = File(original, "build/reports/browser-launcher.log").apply { parentFile.mkdirs() }
        val process = ProcessBuilder(System.getProperty("design.fixture.node"), "--test", script.absolutePath)
            .redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), log.readText())
            assertEquals(0, process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.toList().asReversed().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }

    @Test
    fun `Gradle passes the prepared browser to child processes and honors an override`() {
        val work = createTempDirectory("browser-setup-").toFile()
        try {
            val original = File(System.getProperty("design.fixture.project"))
            original.resolve("../../../gradle/bml-test-browser.gradle.kts")
                .copyTo(work.resolve("browser.gradle.kts"))
            for (name in listOf("bml-test-browser.mjs", "bml-test-browser.test.mjs")) {
                original.resolve("../../../gradle/$name")
                    .copyTo(work.resolve("gradle/$name").apply { parentFile.mkdirs() })
            }
            work.resolve("settings.gradle").writeText("include ':bml:bml-design-catalog', ':consumer'\n")
            val catalog = work.resolve("bml/bml-design-catalog").apply { mkdirs() }
            original.resolve("../../bml-design-catalog/package-lock.json")
                .copyTo(catalog.resolve("package-lock.json"))
            val managed = work.resolve("Managed Browser's Cache/chrome")
            val override = work.resolve("Custom Browser's Cache/chrome")
            for (browser in listOf(managed, override)) {
                browser.parentFile.mkdirs()
                browser.writeText("#!/bin/sh\nexit 0\n")
                assertTrue(browser.setExecutable(true))
            }
            catalog.resolve("build.gradle").writeText("""
                tasks.register('prepareTestBrowser') {
                    def output = layout.buildDirectory.file('test-browser/executable.txt')
                    outputs.file(output)
                    doLast {
                        output.get().asFile.parentFile.mkdirs()
                        output.get().asFile.text = ${quote(managed)}
                    }
                }
            """.trimIndent())
            val consumer = work.resolve("consumer").apply { mkdirs() }
            val junit = File(org.junit.Test::class.java.protectionDomain.codeSource.location.toURI())
            val hamcrest = File(org.hamcrest.Matcher::class.java.protectionDomain.codeSource.location.toURI())
            consumer.resolve("build.gradle").writeText("""
                plugins { id 'java' }
                apply from: '../browser.gradle.kts'
                dependencies { testImplementation files(${quote(junit)}, ${quote(hamcrest)}) }
                test {
                    useJUnit()
                    systemProperty 'expected.browser', System.getenv('EXPECTED_BROWSER')
                }
            """.trimIndent())
            consumer.resolve("src/test/java/BrowserEnvironmentTest.java").apply {
                parentFile.mkdirs()
                writeText("""
                    public class BrowserEnvironmentTest {
                        @org.junit.Test public void launchesSelectedBrowser() throws Exception {
                            String browser = System.getenv("BML_DESIGN_CHROME_BINARY");
                            org.junit.Assert.assertEquals(System.getProperty("expected.browser"), browser);
                            org.junit.Assert.assertEquals(0, new ProcessBuilder(browser, "--version").start().waitFor());
                        }
                    }
                """.trimIndent())
            }
            fun run(browser: File, explicit: Boolean) = GradleRunner.create().withProjectDir(work)
                .withEnvironment((System.getenv() - "BML_DESIGN_CHROME_BINARY") +
                    mapOf("EXPECTED_BROWSER" to browser.absolutePath) +
                    if (explicit) mapOf("BML_DESIGN_CHROME_BINARY" to browser.absolutePath) else emptyMap())
                .withArguments(":consumer:cleanTest", ":consumer:test", "--configuration-cache", "--no-build-cache", "--stacktrace")
                .build()
            assertEquals(TaskOutcome.SUCCESS, run(managed, false).task(":bml:bml-design-catalog:prepareTestBrowser")?.outcome)
            val cached = run(managed, false)
            assertTrue("Reusing configuration cache" in cached.output, cached.output)
            assertEquals(TaskOutcome.SUCCESS, cached.task(":consumer:test")?.outcome)
            assertNull(run(override, true).task(":bml:bml-design-catalog:prepareTestBrowser"))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun quote(file: File) = "'" + file.absolutePath.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
