package bosca.cli.ci

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceReleasePublishingTest {

    private val workspace = File("..").absoluteFile
    private val parser = PipelineYamlParser()
    private val version = "7.4.0"

    @Test
    fun `dedicated image releases accept existing tag refs and reject branches before setup`() = runTest {
        val files = File(workspace, ".bosca/pipelines").listFiles { file ->
            file.name.startsWith("release-image-") && file.extension == "yaml"
        }.orEmpty()
        assertTrue(files.isNotEmpty())
        val root = fixture()
        try {
            for (file in files) {
                val definition = parser.parse(file.readText())
                val validate = definition.jobs.getValue("publish-image").steps.first()
                assertEquals("Validate release tag", validate.name)
                for (ref in listOf("refs/tags/7.4.0", "refs/tags/v7.4.0", "refs/heads/main", "refs/tags/", "commit-sha")) {
                    val result = executor(root).execute(validate, ExpressionContext(ref = ref))
                    assertEquals(ref.startsWith("refs/tags/") && ref.length > "refs/tags/".length, result.success, "${file.name}: $ref")
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `CLI packages and verified checksums publish to Bosca without GitHub credentials`() = runTest {
        val server = MockWebServer().apply { start() }
        val root = fixture()
        try {
            val dist = File(root, "dist").apply { mkdirs() }
            val packages = mapOf(
                "bosca-$version-linux-x86_64.tar.gz" to "linux-package",
                "bosca-$version-macos-arm64.pkg" to "macos-package",
            )
            packages.forEach { (name, body) -> File(dist, name).writeText(body) }
            repeat(3) { server.enqueue(MockResponse.Builder().code(201).build()) }
            val executor = executor(root, registry = server.url("/").toString())
            val context = ExpressionContext(extra = mapOf("inputs.version" to version))
            val steps = pipeline("release-cli.yaml").jobs.getValue("publish").steps
            val checksum = steps.first { it.name == "Generate checksums" }
            assertTrue(executor(File(root, assertNotNull(checksum.workingDirectory))).execute(checksum, context).success)
            assertTrue(executor.execute(steps.first { it.uses == "registry-upload" }, context).success)
            val sums = File(dist, "SHA256SUMS").readText()
            packages.forEach { (name, body) ->
                val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                assertTrue(sums.contains("$digest  $name"))
            }
            for ((name, body) in packages + ("SHA256SUMS" to sums)) {
                val request = server.takeRequest()
                assertEquals("PUT", request.method)
                assertEquals("/raw/bosca/api/bosca-cli/$version/$name", request.target)
                assertEquals(Credentials.basic("api_token", "agent-token"), request.headers["Authorization"])
                assertEquals(body, assertNotNull(request.body).utf8())
            }
            assertEquals("registry-upload", steps.last().uses)
            assertEquals(3, server.requestCount)
        } finally {
            server.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `local image builds keep the bosca namespace and require a registry before pushing`() = runTest {
        for (push in listOf("false", "true")) {
            val root = fixture()
            try {
                copyScript(root, "build-image.sh")
                val requests = File(root, "requests")
                val result = executor(root, environment = dockerEnvironment(root) + mapOf(
                    "PUSH" to push, "BOSCA_REGISTRY_URL" to "", "BOSCA_IMAGE_REGISTRY" to "",
                )).execute(
                    StepDefinition(name = "Build image", run = "scripts/release/build-image.sh bosca-gateway $version"),
                    ExpressionContext(),
                )
                if (push == "false") {
                    assertTrue(result.success)
                    val build = requests.readLines().single()
                    assertTrue(build.contains("-t bosca/bosca-gateway:$version"))
                    assertFalse(build.contains("ghcr.io"))
                } else {
                    assertFalse(result.success)
                    assertFalse(requests.exists(), "missing publication configuration must fail before building or pushing")
                }
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `images publish only to Bosca whether or not GitHub credentials exist`() = runTest {
        val credentials = mapOf("GITHUB_USERNAME" to "publisher", "GITHUB_TOKEN" to "test-github-token")
        for (secrets in listOf(emptyMap(), credentials)) {
            val root = fixture()
            try {
                copyScript(root, "build-image.sh")
                val requests = File(root, "requests")
                val environment = dockerEnvironment(root) + mapOf(
                    "BOSCA_REGISTRY_URL" to "https://artifacts.example.test/",
                    "BOSCA_IMAGE_REGISTRY" to "", "PUSH" to "true",
                )
                val executor = executor(root, environment = environment, secrets = secrets)
                val steps = pipeline("release-image-bosca-gateway.yaml").jobs.getValue("publish-image").steps
                val context = ExpressionContext(
                    ref = "refs/tags/v$version", secrets = secrets, extra = mapOf("inputs.version" to version),
                )
                assertTrue(executor.execute(steps.first { it.name == "Build and push image" }, context).success)
                val source = "artifacts.example.test/bosca/bosca-gateway:$version"
                val calls = requests.readLines()
                assertEquals("Build and push image", steps.last().name)
                assertEquals(2, calls.size)
                assertTrue(calls.first().contains("-t $source"))
                assertEquals("push $source", calls.last())
                assertFalse(calls.any { it.contains("ghcr.io") })
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun pipeline(name: String) = parser.parse(File(workspace, ".bosca/pipelines/$name").readText())

    private fun fixture() = Files.createTempDirectory("release-publishing-test").toFile().apply {
        File(this, ".bosca_env").createNewFile()
    }

    private fun dockerEnvironment(root: File): Map<String, String> {
        val bin = File(root, "bin").apply { mkdirs() }
        File(bin, "docker").apply {
            writeText("""
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\n' "${'$'}*" >> "${'$'}REQUESTS"
            """.trimIndent())
            setExecutable(true)
        }
        return mapOf(
            "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}",
            "REQUESTS" to File(root, "requests").absolutePath,
        )
    }

    private fun copyScript(root: File, name: String) {
        val target = File(root, "scripts/release/$name")
        target.parentFile.mkdirs()
        File(workspace, "scripts/release/$name").copyTo(target)
        target.setExecutable(true)
    }

    private fun executor(
        root: File,
        registry: String = "",
        environment: Map<String, String> = emptyMap(),
        secrets: Map<String, String> = emptyMap(),
    ) = StepExecutor(
        workDir = root, serverUrl = "", agentToken = "agent-token", registryUrl = registry,
        commitSha = "test-commit", ref = "", repositoryId = "r", env = environment, secrets = secrets,
        logBuffer = TestLogBuffer(setOf("agent-token", "test-github-token")),
    )
}
