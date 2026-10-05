package bosca.cli.ci

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepExecutorSetupRegistryTest {

    @Test
    fun `setup-registry scopes npm publishing credentials to the npm endpoint`() = runTest {
        for (registry in listOf("https://artifacts.example.test/", "http://127.0.0.1:8090", "artifacts.example.test")) {
            val root = Files.createTempDirectory("setup-registry-test").toFile()
            try {
                val home = File(root, "home").apply { mkdirs() }
                val bin = File(root, "bin").apply { mkdirs() }
                val login = File(root, "docker-login")
                val token = "test-registry-token"
                File(bin, "docker").apply {
                    writeText("""
                        #!/usr/bin/env bash
                        set -euo pipefail
                        printf '%s\n' "${'$'}*" > "${login.absolutePath}"
                        read -r token
                        [[ "${'$'}token" == "$token" ]]
                    """.trimIndent())
                    setExecutable(true)
                }
                val envFile = File(root, ".bosca_env").apply { createNewFile() }
                val logs = TestLogBuffer(setOf(token))
                val executor = StepExecutor(
                    workDir = root, serverUrl = "", agentToken = token, registryUrl = registry,
                    commitSha = "abc", ref = "refs/tags/1.0.0", repositoryId = "r",
                    env = mapOf("HOME" to home.absolutePath, "PATH" to "${bin.absolutePath}:${System.getenv("PATH")}"),
                    secrets = emptyMap(), logBuffer = logs, sharedEnvFile = envFile,
                )
                val result = executor.execute(
                    StepDefinition(name = "Setup Registry", uses = "setup-registry", with = mapOf("repository" to "bosca-maven")),
                    ExpressionContext(),
                )
                assertTrue(result.success, "setup-registry failed: ${logs.lines}")
                val host = registry.removePrefix("https://").removePrefix("http://").trimEnd('/')
                assertEquals("//$host/npm/:_authToken=$token", File(home, ".npmrc").readText().trim())
                val dockerHost = host.replace("127.0.0.1", "host.docker.internal")
                assertEquals("login $dockerHost --username api_token --password-stdin", login.readText().trim())
                assertTrue(File(home, ".gradle/gradle.properties").readText().contains("/maven/bosca-maven"))
                assertEquals(token, readSharedEnvFile(envFile)["BOSCA_REGISTRY_TOKEN"])
                assertFalse(logs.lines.any { it.contains(token) }, "registry credentials must not appear in logs")
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
