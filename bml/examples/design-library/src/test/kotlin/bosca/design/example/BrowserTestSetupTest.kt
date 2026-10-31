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
                .copyTo(work.resolve("gradle/bml-test-browser.gradle.kts").apply { parentFile.mkdirs() })
            work.resolve("settings.gradle").writeText("include ':bml:bml-design-catalog', ':consumer'\n" +
                "buildCache { local { directory = file(${quote(work.resolve("cache"))}) } }\n")
            for (name in listOf("bml-test-browser.mjs", "bml-test-browser.test.mjs")) {
                original.resolve("../../../gradle/$name")
                    .copyTo(work.resolve("gradle/$name").apply { parentFile.mkdirs() })
            }
            val catalog = work.resolve("bml/bml-design-catalog").apply { mkdirs() }
            original.resolve("../../bml-design-catalog/package-lock.json")
                .copyTo(catalog.resolve("package-lock.json"))
            val managed = work.resolve("Managed Browser's Cache/chrome")
            val override = work.resolve("Custom Browser's Cache/chrome")
            for (browser in listOf(managed, override)) {
                browser.parentFile.mkdirs()
                browser.writeText("#!/bin/sh\necho Chromium-123\n")
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
                apply from: '../gradle/bml-test-browser.gradle.kts'
                dependencies { testImplementation files(${quote(junit)}, ${quote(hamcrest)}) }
                test {
                    useJUnit()
                    def expected = System.getenv('EXPECTED_BROWSER')
                    doFirst { systemProperty 'expected.browser', expected }
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
            fun run(browser: File, explicit: Boolean, clean: Boolean = true, projectRoot: File = work) =
                GradleRunner.create().withProjectDir(projectRoot)
                .withEnvironment((System.getenv() - "BML_DESIGN_CHROME_BINARY") +
                    mapOf("EXPECTED_BROWSER" to browser.absolutePath) +
                    if (explicit) mapOf("BML_DESIGN_CHROME_BINARY" to browser.absolutePath) else emptyMap())
                .withArguments((if (clean) listOf(":consumer:cleanTest") else emptyList()) +
                    listOf(":consumer:test", "--configuration-cache", "--build-cache", "--stacktrace"))
                .build()
            val identity = consumer.resolve("src/main/resources/META-INF/bosca/bml/build-id")
            identity.parentFile.mkdirs()
            identity.writeText("first-build")
            assertEquals(TaskOutcome.SUCCESS, run(managed, false).task(":bml:bml-design-catalog:prepareTestBrowser")?.outcome)
            // The new identity alone must not invalidate successful behavioral checks.
            identity.writeText("second-build")
            val cached = run(managed, false)
            assertTrue("Reusing configuration cache" in cached.output, cached.output)
            assertEquals(TaskOutcome.FROM_CACHE, cached.task(":consumer:test")?.outcome)
            assertEquals(TaskOutcome.UP_TO_DATE, run(managed, false, clean = false).task(":consumer:test")?.outcome)
            val overridden = run(override, true)
            assertNull(overridden.task(":bml:bml-design-catalog:prepareTestBrowser"))
            assertEquals(TaskOutcome.FROM_CACHE, overridden.task(":consumer:test")?.outcome)
            val relocated = work.resolve("relocated").apply { mkdirs() }
            for (path in listOf("settings.gradle", "gradle/bml-test-browser.gradle.kts",
                "consumer/build.gradle", "consumer/src/test/java/BrowserEnvironmentTest.java",
                "consumer/src/main/resources/META-INF/bosca/bml/build-id", "bml/bml-design-catalog/build.gradle",
                "gradle/bml-test-browser.mjs", "gradle/bml-test-browser.test.mjs", "bml/bml-design-catalog/package-lock.json")) {
                work.resolve(path).copyTo(relocated.resolve(path).apply { parentFile.mkdirs() })
            }
            val relocatedBrowser = relocated.resolve("chrome")
            override.copyTo(relocatedBrowser)
            assertTrue(relocatedBrowser.setExecutable(true))
            assertEquals(TaskOutcome.FROM_CACHE,
                run(relocatedBrowser, true, projectRoot = relocated).task(":consumer:test")?.outcome)
            relocated.resolve("consumer/src/test/resources/changed.txt").apply {
                parentFile.mkdirs()
                writeText("resource change must invalidate cached results")
            }
            assertEquals(TaskOutcome.SUCCESS,
                run(relocatedBrowser, true, projectRoot = relocated).task(":consumer:test")?.outcome)
            override.writeText("#!/bin/sh\necho Chromium-124\n")
            assertEquals(TaskOutcome.SUCCESS, run(override, true).task(":consumer:test")?.outcome)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun quote(file: File) = "'" + file.absolutePath.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
