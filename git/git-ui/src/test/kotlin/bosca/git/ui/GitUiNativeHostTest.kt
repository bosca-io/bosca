package bosca.git.ui

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/** Exercises the production native UI host with only an executable and explicit local configuration. */
class GitUiNativeHostTest {
    @Test
    fun `standalone native host serves design assets and preserves Git editor workflows`() {
        val binary = File(requireNotNull(System.getProperty("gitUi.native.binary"))).canonicalFile
        val report = File(requireNotNull(System.getProperty("gitUi.native.resourceReport"))).canonicalFile
        val browser = File(requireNotNull(System.getProperty("gitUi.native.browser"))).canonicalFile
        val artifacts = File("build/reports/native-ui").absoluteFile.apply { mkdirs() }
        val directory = createTempDirectory("git-ui-native-").toFile()
        var server: Process? = null
        var browserProcess: Process? = null
        try {
            assertTrue(binary.isFile, binary.path)
            verifyGitUiNativeResources(report, File(artifacts, "embedded-resources.json"))
            val executable = binary.copyTo(File(directory, binary.name))
            assertTrue(executable.setExecutable(true), executable.path)
            assertEquals(digest(binary.readBytes()), digest(executable.readBytes()))
            GitUiRoutesTest.startApiFixture()
            val config = File(directory, "git-ui-server.yaml").apply {
                writeText("""
                    bosca:
                      server:
                        port: 0
                        drain-timeout-ms: 0
                        development: false
                    bml:
                      graphqlEndpoint: ${JsonPrimitive(GitUiRoutesTest.browserFixtureGraphqlEndpoint())}
                      authCookiePrefix: _git_test
                """.trimIndent())
            }
            assertEquals(setOf(executable.name, config.name), directory.listFiles().orEmpty().map { it.name }.toSet())
            val log = File(artifacts, "server.log")
            val process = ProcessBuilder(executable.absolutePath, "-config=${config.absolutePath}")
                .directory(directory).redirectErrorStream(true).redirectOutput(log).start()
            server = process
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val base = awaitServer(process, log)
            val ready = client.send(HttpRequest.newBuilder(base.resolve("/api/v1/ready"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, ready.statusCode(), ready.body())
            assertEquals("ready", ready.body())
            GitUiAssetAudit(client, base, File(artifacts, "page-assets.json")).verify()
            val browserLog = File(artifacts, "browser.log")
            val checks = ProcessBuilder(requireNotNull(System.getProperty("gitUi.native.node")), browser.absolutePath)
                .directory(directory).apply {
                    environment()["GIT_UI_BASE_URL"] = base.toString().trimEnd('/')
                    environment()["GIT_UI_BROWSER_TESTS"] = "source.mjs,repository.mjs,json-editor.mjs"
                    environment()["GIT_UI_ARTIFACT_DIR"] = File(artifacts, "browser").absolutePath
                    redirectErrorStream(true)
                    redirectOutput(browserLog)
                }.start()
            browserProcess = checks
            assertTrue(checks.waitFor(180, TimeUnit.SECONDS), "Native browser checks timed out: ${browserLog.readText()}")
            assertEquals(0, checks.exitValue(), browserLog.readText() + "\nNative host:\n" + log.readText())
            assertTrue(process.isAlive, log.readText())
            assertFalse("bml: no META-INF/bosca/bml/build-id resource" in log.readText(), log.readText())
            File(artifacts, "executable.txt").writeText("name=${binary.name}\nbytes=${binary.length()}\nsha256=${digest(binary.readBytes())}\norigin=$base\n")
        } finally {
            browserProcess?.let { stop(it) }
            server?.let { stop(it) }
            GitUiRoutesTest.stop()
            Files.walk(directory.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
        }
    }

    private fun awaitServer(process: Process, log: File): URI {
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (process.isAlive && System.nanoTime() < deadline) {
            val port = Regex("Bosca server started on port ([0-9]+) with").find(log.readText())
                ?.groupValues?.get(1)?.toIntOrNull()
            if (port != null) {
                assertTrue(port in 1..65535, log.readText())
                return URI("http://localhost:$port/")
            }
            Thread.sleep(25)
        }
        error("Native Git UI did not start (alive=${process.isAlive}): ${log.readText()}")
    }

    private fun stop(process: Process) {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Process ${process.pid()} did not stop")
            }
        }
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
