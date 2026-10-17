package bosca.cli.artifacts

import bosca.cli.config.CliConfigStore
import bosca.cli.config.CliInvocation
import com.github.ajalt.clikt.testing.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactsLoginCommandTest {
    private lateinit var directory: File
    private lateinit var server: MockWebServer
    private lateinit var docker: File
    private lateinit var npmrc: File
    private val artifactToken = "test-artifact-pull-token"

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("bosca-artifacts-login").toFile()
        CliConfigStore.directoryOverride = File(directory, "bosca")
        CliConfigStore.workingDirectoryOverride = directory
        CliInvocation.selectProfile(null)
        server = MockWebServer().apply { start() }
        npmrc = File(directory, "npm/user.npmrc")
        docker = File(directory, "docker")
        configureDocker(0)
    }

    @AfterTest
    fun tearDown() {
        server.close()
        CliInvocation.selectProfile(null)
        CliConfigStore.workingDirectoryOverride = null
        CliConfigStore.directoryOverride = null
        directory.deleteRecursively()
    }

    @Test
    fun `login configures Docker and a real authenticated npm request`() {
        enqueueToken()
        val registry = server.url("/").toString()
        val result = ArtifactsLoginCommand(docker.absolutePath, npmrc).test(
            "--url ${server.url("/graphql")} --token caller-token --registry $registry",
        )

        assertEquals(0, result.statusCode, result.output)
        assertTrue("npm @bosca registry configured" in result.stdout, result.output)
        assertFalse(artifactToken in result.output)
        assertEquals(artifactToken, File("${docker.path}.token").readText())
        assertEquals(listOf("login", server.url("/").let { "${it.host}:${it.port}" },
            "--username", "api_token", "--password-stdin"), File("${docker.path}.args").readLines())
        val create = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("Bearer caller-token", create?.headers?.get("Authorization"))
        val input = Json.parseToJsonElement(create?.body?.utf8().orEmpty()).jsonObject
            .getValue("variables").jsonObject.getValue("input").jsonObject
        assertEquals(listOf("artifacts:pull"), input.getValue("scopes").jsonArray.map { it.jsonPrimitive.content })

        server.enqueue(MockResponse.Builder().body("""
            {"name":"@bosca/bml","dist-tags":{"latest":"1.0.0"},"versions":{"1.0.0":{"name":"@bosca/bml","version":"1.0.0"}}}
        """.trimIndent()).build())
        val output = File(directory, "npm-output")
        val process = ProcessBuilder(
            "npm", "view", "@bosca/bml", "version", "--userconfig=${npmrc.absolutePath}",
            "--prefer-online", "--fetch-retries=0", "--fetch-timeout=5000", "--loglevel=error",
        ).directory(directory).redirectErrorStream(true).redirectOutput(output).apply {
            environment().keys.removeIf { it.startsWith("npm_config_", ignoreCase = true) }
            environment()["NPM_CONFIG_CACHE"] = File(directory, "npm-cache").absolutePath
            environment()["NPM_CONFIG_UPDATE_NOTIFIER"] = "false"
        }.start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "npm did not finish")
            assertEquals(0, process.exitValue(), output.readText())
            assertEquals("1.0.0", output.readText().trim())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("/npm/@bosca%2fbml", request?.target?.substringBefore('?')?.lowercase())
        assertEquals("Bearer $artifactToken", request?.headers?.get("Authorization"))
    }

    @Test
    fun `default local registry configures npm over HTTP`() {
        enqueueToken()
        val result = ArtifactsLoginCommand(docker.absolutePath, npmrc).test(
            "--url ${server.url("/graphql")} --token caller-token",
        )

        assertTrue("Logged in to localhost:8084" in result.stdout, result.output)
        assertTrue("@bosca:registry=http://localhost:8084/npm/" in npmrc.readText())
    }

    @Test
    fun `failed Docker login revokes the token and leaves npm settings intact`() {
        configureDocker(1)
        npmrc.parentFile.mkdirs()
        val existing = "@bosca:registry=https://old.example/npm/\n"
        npmrc.writeText(existing)
        enqueueToken()
        server.enqueue(MockResponse.Builder().body("""
            {"data":{"security":{"apiTokens":{"revoke":true}}}}
        """.trimIndent()).build())
        val result = ArtifactsLoginCommand(docker.absolutePath, npmrc).test(
            "--url ${server.url("/graphql")} --token caller-token --registry artifacts.example",
        )

        assertTrue("Docker login failed" in result.stderr, result.output)
        assertFalse("npm @bosca registry configured" in result.stdout)
        assertFalse(artifactToken in result.output)
        assertEquals(existing, npmrc.readText())
        server.takeRequest(5, TimeUnit.SECONDS)
        val revoke = Json.parseToJsonElement(server.takeRequest(5, TimeUnit.SECONDS)?.body?.utf8().orEmpty()).jsonObject
        assertEquals("RevokeApiToken", revoke.getValue("operationName").jsonPrimitive.content)
        assertEquals("42", revoke.getValue("variables").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun `npm write failure reports an error without revoking the token already used by Docker`() {
        npmrc.mkdirs()
        enqueueToken()
        val result = ArtifactsLoginCommand(docker.absolutePath, npmrc).test(
            "--url ${server.url("/graphql")} --token caller-token --registry artifacts.example",
        )

        assertTrue("Error:" in result.stderr, result.output)
        assertFalse("npm @bosca registry configured" in result.stdout)
        assertFalse(artifactToken in result.output)
        assertEquals(artifactToken, File("${docker.path}.token").readText())
        assertEquals(1, server.requestCount)
        assertTrue(npmrc.isDirectory)
    }

    private fun configureDocker(exitCode: Int) {
        docker.writeText("""
            #!/bin/sh
            printf '%s\n' "${'$'}@" > "${'$'}0.args"
            cat > "${'$'}0.token"
            exit $exitCode
        """.trimIndent())
        assertTrue(docker.setExecutable(true))
    }

    private fun enqueueToken() {
        server.enqueue(MockResponse.Builder().body("""
            {"data":{"security":{"apiTokens":{"create":{
              "apiToken":{"id":42,"principalId":"00000000-0000-0000-0000-000000000001",
                "name":"artifacts-login","description":null,"tokenPrefix":"test-prefix",
                "scopes":["artifacts:pull"],"allowedGroups":null,"expiresAt":null,
                "lastUsedAt":null,"lastUsedIp":null,"revokedAt":null,"active":true},
              "rawToken":"$artifactToken"
            }}}}}
        """.trimIndent()).build())
    }
}
