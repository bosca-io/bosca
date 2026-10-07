package bosca.cli.update

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallScriptTest {
    @Test
    fun latestArtifactsVersionInstallsLinuxPackageWithAuthenticatedDownloads() {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().body(
                """{"name":"bosca-cli","versions":[
                    {"version":"6.11.0-rc1","files":[]},
                    {"version":"latest","files":[]},
                    {"version" : "6.10.0","files":[]},
                    {"version":"6.9.0","files":[]}
                ]}"""
            ).build())
            fixture.enqueuePackage("6.10.0")

            val result = fixture.run(mapOf("BOSCA_CLI_ARTIFACTS_TOKEN" to "bsk_fixture"))

            assertEquals(0, result.first, result.second)
            assertEquals(fixture.binary, File(fixture.installDir, "bosca").readText())
            assertContains(result.second, "Checksum verified")
            fixture.assertRequests(
                listOf("/raw/bosca/api/bosca-cli", "/raw/bosca/bosca-cli/6.10.0/bosca-6.10.0-linux-x86_64.tar.gz",
                    "/raw/bosca/bosca-cli/6.10.0/SHA256SUMS"),
                "Bearer bsk_fixture",
            )
            assertFalse(result.second.contains("api.github.com"))
        }
    }

    @Test
    fun exactArtifactsVersionSkipsListingAndDoesNotSendGitHubToken() {
        Fixture().use { fixture ->
            fixture.enqueuePackage("6.9.0")
            val result = fixture.run(mapOf("BOSCA_VERSION" to "6.9.0", "GITHUB_TOKEN" to "github_fixture"))

            assertEquals(0, result.first, result.second)
            fixture.assertRequests(listOf("/raw/bosca/bosca-cli/6.9.0/bosca-6.9.0-linux-x86_64.tar.gz",
                "/raw/bosca/bosca-cli/6.9.0/SHA256SUMS"))
        }
    }

    @Test
    fun checksumMismatchPreservesInstalledBinary() {
        Fixture().use { fixture ->
            File(fixture.installDir, "bosca").writeText("previous binary")
            fixture.enqueuePackage("6.9.0", corruptChecksum = true)

            val result = fixture.run(mapOf("BOSCA_VERSION" to "6.9.0"))

            assertEquals(1, result.first, result.second)
            assertContains(result.second, "checksum mismatch")
            assertEquals("previous binary", File(fixture.installDir, "bosca").readText())
        }
    }

    @Test
    fun emptyArtifactsListingFailsBeforeInstalling() {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().body("""{"name":"bosca-cli","versions":[]}""").build())

            val result = fixture.run()

            assertEquals(1, result.first, result.second)
            assertContains(result.second, "could not find a stable CLI version")
            assertFalse(File(fixture.installDir, "bosca").exists())
            fixture.assertRequests(listOf("/raw/bosca/api/bosca-cli"))
        }
    }

    @Test
    fun missingArtifactsPackageFailsWithoutInstalling() {
        Fixture().use { fixture ->
            fixture.server.enqueue(MockResponse.Builder().code(404).build())

            val result = fixture.run(mapOf("BOSCA_VERSION" to "6.9.0"))

            assertEquals(1, result.first, result.second)
            assertContains(result.second, "download failed")
            assertFalse(File(fixture.installDir, "bosca").exists())
            fixture.assertRequests(listOf("/raw/bosca/bosca-cli/6.9.0/bosca-6.9.0-linux-x86_64.tar.gz"))
        }
    }

    @Test
    fun macosArtifactsPackageReachesSystemInstallerAfterVerification() {
        Fixture(os = "Darwin", arch = "arm64").use { fixture ->
            fixture.enqueuePackage("6.9.0")

            val result = fixture.run(mapOf("BOSCA_VERSION" to "6.9.0"))

            assertEquals(0, result.first, result.second)
            assertContains(result.second, "Checksum verified")
            val arguments = File(fixture.root, "installer-arguments").readLines()
            assertEquals("-pkg", arguments[0])
            assertTrue(arguments[1].endsWith("/bosca-6.9.0-macos-arm64.pkg"))
            assertEquals(listOf("-target", "/"), arguments.drop(2))
            fixture.assertRequests(listOf("/raw/bosca/bosca-cli/6.9.0/bosca-6.9.0-macos-arm64.pkg",
                "/raw/bosca/bosca-cli/6.9.0/SHA256SUMS"))
        }
    }

    private class Fixture(private val os: String = "Linux", private val arch: String = "x86_64") : AutoCloseable {
        val root: File = Files.createTempDirectory("bosca-installer-test").toFile()
        val installDir = File(root, "installed").apply { mkdirs() }
        val server = MockWebServer().apply { start() }
        val binary = "#!/bin/sh\nprintf 'bosca fixture\\n'\n"
        private val toolsDir = File(root, "tools").apply { mkdirs() }

        init {
            tool("uname", "#!/bin/sh\ncase \"\$1\" in -s) echo '$os';; -m) echo '$arch';; *) exit 1;; esac\n")
            // Privileged commands are stubs; this test never installs into the host's system directories.
            tool("id", "#!/bin/sh\necho 0\n")
            tool("sudo", "#!/bin/sh\nexit 99\n")
            tool("installer", "#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$INSTALLER_TEST_ROOT/installer-arguments\"\n")
        }

        private fun tool(name: String, body: String) {
            File(toolsDir, name).apply { writeText(body); setExecutable(true) }
        }

        fun enqueuePackage(version: String, corruptChecksum: Boolean = false) {
            val platform = if (os == "Darwin") "macos-$arch" else "linux-$arch"
            val filename = "bosca-$version-$platform" + if (os == "Darwin") ".pkg" else ".tar.gz"
            val bytes = if (os == "Darwin") {
                "macos fixture package".toByteArray()
            } else {
                val sourceDir = File(root, "source").apply { mkdirs() }
                File(sourceDir, "bosca").writeText(binary)
                val archive = File(root, filename)
                val tar = ProcessBuilder("tar", "-czf", archive.absolutePath, "-C", sourceDir.absolutePath, "bosca").start()
                assertTrue(tar.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, tar.exitValue())
                archive.readBytes()
            }
            val digest = if (corruptChecksum) "0".repeat(64) else {
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
            server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
            server.enqueue(MockResponse.Builder().body("$digest  $filename\n").build())
        }

        fun run(extraEnvironment: Map<String, String> = emptyMap()): Pair<Int, String> {
            val log = File(root, "output.log")
            val builder = ProcessBuilder("/bin/sh", File("../cli/install.sh").absolutePath)
                .redirectErrorStream(true).redirectOutput(log)
            builder.environment().apply {
                listOf("BOSCA_VERSION", "BOSCA_CLI_ARTIFACTS_TOKEN", "BOSCA_CLI_REPOSITORY", "GITHUB_TOKEN").forEach { remove(it) }
                put("PATH", toolsDir.absolutePath + ":/usr/bin:/bin")
                put("BOSCA_CLI_ARTIFACTS_URL", server.url("/raw/bosca/bosca-cli/").toString())
                put("BOSCA_INSTALL_DIR", installDir.absolutePath)
                put("INSTALLER_TEST_ROOT", root.absolutePath)
                putAll(extraEnvironment)
            }
            val process = builder.start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Installer timed out: " + log.readText())
            }
            return process.exitValue() to log.readText()
        }

        fun assertRequests(paths: List<String>, authorization: String? = null) {
            assertEquals(paths.size, server.requestCount)
            paths.forEach { path ->
                val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(path, request.target)
                assertEquals(authorization, request.headers["Authorization"])
            }
        }

        override fun close() {
            server.close()
            root.deleteRecursively()
        }
    }
}
