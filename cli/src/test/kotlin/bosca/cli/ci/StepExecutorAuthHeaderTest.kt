package bosca.cli.ci

import bosca.cli.api.NetworkClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Regression coverage for the auth header scoping in [StepExecutor.authHeaderConfigEnv].
 *
 * git matches `http.<url>.*` config keys by exact port ("omitted port numbers are
 * automatically converted to the correct default for the scheme before matching"),
 * so a clone URL on a non-default port (e.g. a local git-server on :8091) must have
 * that port in the config key — otherwise git never sends the Authorization header
 * and the server rejects the clone as anonymous with a 401.
 */
class StepExecutorAuthHeaderTest {

    private val zero = Uuid.parse("00000000-0000-0000-0000-000000000000")

    private class SilentLogBuffer : LogBuffer(
        api = NoNetworkAuthHeaderCiApi,
        repositoryId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        runId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        jobId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        stepId = Uuid.parse("00000000-0000-0000-0000-000000000000"),
        secretValues = emptySet(),
    ) {
        override fun addLine(content: String, stream: String) {}
        override suspend fun add(content: String, stream: String) {}
        override suspend fun flush() {}
        override suspend fun close() {}
    }

    private fun newExecutor(cloneUrl: String) = StepExecutor(
        workDir = File("."),
        serverUrl = "http://localhost:0",
        agentToken = "agent-token",
        commitSha = "deadbeef",
        ref = "refs/heads/main",
        repositoryId = zero.toString(),
        cloneUrl = cloneUrl,
        env = emptyMap(),
        secrets = emptyMap(),
        logBuffer = SilentLogBuffer(),
    )

    @Test
    fun `clone url with a non-default port scopes the header to that port`() {
        val env = newExecutor("http://localhost:8091/owner/repo.git").authHeaderConfigEnv()

        assertEquals("http.http://localhost:8091/.extraHeader", env["GIT_CONFIG_KEY_0"])
        assertEquals("Authorization: Bearer agent-token", env["GIT_CONFIG_VALUE_0"])
        assertEquals("1", env["GIT_CONFIG_COUNT"])
    }

    @Test
    fun `clone url without a port omits the port so git matches the scheme default`() {
        val env = newExecutor("https://git.example.com/owner/repo.git").authHeaderConfigEnv()

        assertEquals("http.https://git.example.com/.extraHeader", env["GIT_CONFIG_KEY_0"])
        assertEquals("Authorization: Bearer agent-token", env["GIT_CONFIG_VALUE_0"])
    }

    @Test
    fun `clone url with an explicit default port keeps it in the config key`() {
        // git normalizes both the config key and the request URL to numeric ports
        // before comparing, so an explicit :443 still matches https://host/ requests
        val env = newExecutor("https://git.example.com:443/owner/repo.git").authHeaderConfigEnv()

        assertEquals("http.https://git.example.com:443/.extraHeader", env["GIT_CONFIG_KEY_0"])
    }

    @Test
    fun `unparseable clone url falls back to an unscoped header`() {
        val env = newExecutor("not a url").authHeaderConfigEnv()

        assertEquals("http.extraHeader", env["GIT_CONFIG_KEY_0"])
        assertEquals("Authorization: Bearer agent-token", env["GIT_CONFIG_VALUE_0"])
    }

    @Test
    fun `git prompts are disabled in every case`() {
        val env = newExecutor("http://localhost:8091/owner/repo.git").authHeaderConfigEnv()

        assertEquals("0", env["GIT_TERMINAL_PROMPT"])
        assertEquals("echo", env["GIT_ASKPASS"])
    }
}

private object NoNetworkAuthHeaderCiApi : CiApi(NetworkClient("http://localhost:0"))
