package bosca.cli.compose

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun executeComposeTestInstaller(arguments: List<String>, directory: Path) {
    val process = ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor() != 0) throw CliktError(output)
}

/** The proxy tests use real CLI-rendered installations rather than handwritten template substitutions. */
internal fun installedComposeNginx(directory: Path, separate: Boolean = false): String {
    val arguments = listOf("--directory", directory.toString(), "--domain", "example.test") +
        if (separate) listOf("--separate-git", "--separate-artifacts", "--separate-ui") else emptyList()
    val result = ComposeInitCommand(::executeComposeTestInstaller).test(arguments)
    assertEquals(0, result.statusCode, result.output)
    return Files.readString(directory.resolve("config/nginx.conf"))
}

class ComposeCommandsTest {
    @Test
    fun `CLI bundles the installer and preserves credentials when topology changes`() {
        val directory = Files.createTempDirectory("compose-cli-")
        try {
            val installation = directory.resolve("installation with spaces")
            installedComposeNginx(installation, separate = true)
            val env = installation.resolve(".env")
            val credentials = Files.readString(env)
            assertTrue(credentials.contains("SEPARATE_GIT=true"))
            assertTrue(credentials.contains("SEPARATE_ARTIFACTS=true"))
            assertTrue(credentials.contains("SEPARATE_UI=true"))
            val before = Files.readString(installation.resolve("compose.yaml"))
            assertTrue(before.contains("  git:"))
            assertTrue(before.contains("  artifacts:"))
            assertTrue(before.contains("  atlas-ui:"))
            val updated = credentials.replace("SEPARATE_GIT=true", "SEPARATE_GIT=false")
                .replace("SEPARATE_ARTIFACTS=true", "SEPARATE_ARTIFACTS=false")
                .replace("SEPARATE_UI=true", "SEPARATE_UI=false")
            Files.writeString(env, updated)
            val result = ComposeRenderCommand(::executeComposeTestInstaller).test(listOf("--directory", installation.toString()))
            assertEquals(0, result.statusCode, result.output)
            assertEquals(updated, Files.readString(env))
            val composed = Files.readString(installation.resolve("compose.yaml"))
            assertFalse(composed.contains("  git:"))
            assertFalse(composed.contains("  artifacts:"))
            assertFalse(composed.contains("  atlas-ui:"))
            assertTrue(composed.contains("for port in 8094 8095"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `CLI uses argument arrays and renders before running Docker commands without removing data`() {
        val directory = Files.createTempDirectory("compose-cli-execution-")
        try {
            val installation = directory.resolve("installation with spaces")
            installedComposeNginx(installation)
            val calls = mutableListOf<List<String>>()
            val execute: ComposeProcess = { arguments, cwd ->
                calls += arguments
                if (arguments.first() == "python3") executeComposeTestInstaller(arguments, cwd)
            }
            val args = listOf("--directory", installation.toString())
            assertEquals(0, ComposeUpCommand(execute).test(args).statusCode)
            assertEquals("python3", calls[0].first())
            assertEquals(listOf("sh", installation.resolve("compose.sh").toString(), "up", "-d", "--wait",
                "--wait-timeout", "300", "--remove-orphans"), calls[1])
            calls.clear()
            assertEquals(0, ComposeDownCommand(execute).test(args).statusCode)
            assertEquals(listOf("sh", installation.resolve("compose.sh").toString(), "down", "--remove-orphans"), calls[1])
            assertFalse(calls.flatten().any { it == "--volumes" || it == "-v" })
            calls.clear()
            assertEquals(0, ComposeConfigCommand(execute).test(args).statusCode)
            assertEquals(listOf("config", "--quiet"), calls[1].takeLast(2))
            calls.clear()
            assertEquals(0, ComposeStatusCommand(execute).test(args).statusCode)
            assertEquals("ps", calls[1].last())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun `CLI rejects missing installations and never starts Docker on invalid public settings`() {
        val directory = Files.createTempDirectory("compose-cli-invalid-")
        try {
            var calls = 0
            val execute: ComposeProcess = { _, _ -> calls++ }
            assertEquals(1, ComposeUpCommand(execute).test(listOf("--directory", directory.resolve("missing").toString())).statusCode)
            assertEquals(0, calls)
            val invalid = ComposeInitCommand(::executeComposeTestInstaller).test(listOf("--directory", directory.resolve("invalid").toString(), "--domain", "https://example.test"))
            assertEquals(1, invalid.statusCode)
            assertFalse(Files.exists(directory.resolve("invalid")))
        } finally { directory.toFile().deleteRecursively() }
    }
}
