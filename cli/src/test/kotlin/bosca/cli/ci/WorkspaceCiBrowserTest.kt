package bosca.cli.ci

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceCiBrowserTest {
    private val steps = PipelineYamlParser().parse(File("../.bosca/pipelines/ci.yaml").readText())
        .jobs.getValue("test-jvm").steps
    private val install = steps.single { it.name == "Install BML Test Browser" }
    private val browsers = steps.single { it.name == "Run BML Browser Tests" }
    private val tests = steps.single { it.name == "Run JVM Tests" }

    @Test
    fun `CI installs a browser and passes its quoted path to the JVM test step`() = runTest {
        verifySetup(provisionBrowser = true)
    }

    @Test
    fun `CI rejects an installer that does not provide an executable browser`() = runTest {
        verifySetup(provisionBrowser = false)
    }

    @Test
    fun `CI and release generate JVM providers within the shared worker budget`() {
        for (pipeline in listOf("ci", "release")) {
            val pipelineSteps = PipelineYamlParser().parse(File("../.bosca/pipelines/$pipeline.yaml").readText())
                .jobs.getValue("test-jvm").steps
            val command = pipelineSteps.single { it.name == "Run JVM Tests" }.run.orEmpty()
                .split(Regex("\\s+"))
            assertTrue("-Pbosca.kmp.jvmCodegen=true" in command, pipeline)
            assertTrue("--max-workers=4" in command, pipeline)
            assertTrue("test" in command, pipeline)
        }
    }

    private suspend fun verifySetup(provisionBrowser: Boolean) {
        assertTrue(steps.indexOf(install) < steps.indexOf(browsers))
        assertTrue(steps.indexOf(browsers) < steps.indexOf(tests))
        val root = Files.createTempDirectory("ci-bml-browser-").toFile().canonicalFile
        try {
            val catalog = File(root, "bml/bml-design-catalog").apply { mkdirs() }
            val environmentFile = File(root, ".bosca_env").apply { createNewFile() }
            val browser = File(root, "Chromium Test's ${'$'}(touch substituted) `touch backticks`/chrome")
            val bin = File(root, "bin").apply { mkdirs() }
            fun executable(file: File, source: String) {
                file.writeText("#!/usr/bin/env bash\nset -eu\n" + source.trimIndent() + "\n")
                check(file.setExecutable(true))
            }
            executable(File(bin, "npm"), """
                test "${'$'}PWD" = "${'$'}EXPECTED_CATALOG"
                test "${'$'}*" = 'ci --no-audit --no-fund'
            """)
            executable(File(bin, "node"), """
                if [ "${'$'}1" = '-p' ]; then
                    printf '%s\n' "${'$'}BROWSER_PATH"
                else
                    test "${'$'}*" = 'node_modules/playwright-core/cli.js install --no-shell chromium'
                    if [ "${'$'}INSTALL_BROWSER" = true ]; then
                        mkdir -p "${'$'}(dirname "${'$'}BROWSER_PATH")"
                        printf '#!/bin/sh\nexit 0\n' > "${'$'}BROWSER_PATH"
                        chmod +x "${'$'}BROWSER_PATH"
                    fi
                fi
            """)
            executable(File(root, "gradlew"), """
                test -x "${'$'}BML_DESIGN_CHROME_BINARY"
                printf '%s' "${'$'}BML_DESIGN_CHROME_BINARY" > selected-browser.txt
                "${'$'}BML_DESIGN_CHROME_BINARY" --version
            """)
            val logs = TestLogBuffer()
            val environment = mapOf(
                "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}",
                "BROWSER_PATH" to browser.absolutePath,
                "INSTALL_BROWSER" to provisionBrowser.toString(),
                "EXPECTED_CATALOG" to catalog.absolutePath,
            )
            fun executor(step: StepDefinition) = StepExecutor(
                workDir = step.workingDirectory?.let { File(root, it) } ?: root,
                serverUrl = "", agentToken = "", commitSha = "test", ref = "", repositoryId = "test",
                env = environment, secrets = emptyMap(), logBuffer = logs, sharedEnvFile = environmentFile,
            )
            val installed = executor(install).execute(install, ExpressionContext())
            assertEquals(provisionBrowser, installed.success, logs.lines.joinToString("\n"))
            if (provisionBrowser) {
                assertTrue(executor(browsers).execute(browsers, ExpressionContext()).success, logs.lines.joinToString("\n"))
                assertTrue(executor(tests).execute(tests, ExpressionContext()).success, logs.lines.joinToString("\n"))
                assertEquals(browser.absolutePath, File(root, "selected-browser.txt").readText())
            } else {
                assertTrue(environmentFile.readText().isBlank())
                assertFalse(File(root, "selected-browser.txt").exists())
            }
            assertFalse(File(root, "substituted").exists())
            assertFalse(File(root, "backticks").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
