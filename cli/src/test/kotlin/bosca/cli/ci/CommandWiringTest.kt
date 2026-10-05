package bosca.cli.ci

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class CommandWiringTest {

    @Test
    fun `ci command shows help with subcommands`() {
        val result = CiCommand().subcommands(
            CiAgentCommand().subcommands(
                AgentRegisterCommand(),
                AgentListCommand(),
                AgentDeregisterCommand(),
                AgentStartCommand(),
            ),
            CiRunCommand().subcommands(
                RunTriggerCommand(),
                RunListCommand(),
                RunCancelCommand(),
                RunRerunCommand(),
                RunLogsCommand(),
            ),
            CiSecretCommand().subcommands(
                SecretSetCommand(),
                SecretListCommand(),
                SecretDeleteCommand(),
            ),
        ).test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("agent"), "Expected 'agent' subcommand in help: ${result.stdout}")
        assertTrue(result.stdout.contains("run"), "Expected 'run' subcommand in help: ${result.stdout}")
        assertTrue(result.stdout.contains("secret"), "Expected 'secret' subcommand in help: ${result.stdout}")
    }

    @Test
    fun `ci agent command shows help`() {
        val result = CiAgentCommand().subcommands(
            AgentRegisterCommand(),
            AgentListCommand(),
            AgentDeregisterCommand(),
            AgentStartCommand(),
        ).test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("register"))
        assertTrue(result.stdout.contains("list"))
        assertTrue(result.stdout.contains("deregister"))
        assertTrue(result.stdout.contains("start"))
    }

    @Test
    fun `ci run command shows help`() {
        val result = CiRunCommand().subcommands(
            RunTriggerCommand(),
            RunListCommand(),
            RunCancelCommand(),
            RunRerunCommand(),
            RunLogsCommand(),
        ).test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("trigger"))
        assertTrue(result.stdout.contains("list"))
        assertTrue(result.stdout.contains("cancel"))
        assertTrue(result.stdout.contains("rerun"))
        assertTrue(result.stdout.contains("logs"))
    }

    @Test
    fun `ci secret command shows help`() {
        val result = CiSecretCommand().subcommands(
            SecretSetCommand(),
            SecretListCommand(),
            SecretDeleteCommand(),
        ).test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.contains("set"))
        assertTrue(result.stdout.contains("list"))
        assertTrue(result.stdout.contains("delete"))
    }

    @Test
    fun `agent register requires name and labels`() {
        val result = AgentRegisterCommand().test("")
        assertTrue(result.statusCode != 0 || result.stderr.contains("Error"),
            "Expected error without required args")
    }

    @Test
    fun `run trigger requires pipeline-id and ref`() {
        val result = RunTriggerCommand().test("")
        assertTrue(result.statusCode != 0 || result.stderr.contains("Error"),
            "Expected error without required args")
    }

    @Test
    fun `secret set requires repo-id name and value`() {
        val result = SecretSetCommand().test("")
        assertTrue(result.statusCode != 0 || result.stderr.contains("Error"),
            "Expected error without required args")
    }

    @Test
    fun `run list requires repo-id`() {
        val result = RunListCommand().test("")
        assertTrue(result.statusCode != 0 || result.stderr.contains("Error"),
            "Expected error without required args")
    }

    @Test
    fun `run controls expose exact job targeting`() {
        val rerun = RunRerunCommand().test("--help")
        val cancel = RunCancelCommand().test("--help")
        assertEquals(0, rerun.statusCode)
        assertEquals(0, cancel.statusCode)
        assertTrue(rerun.stdout.contains("--job-id"))
        assertTrue(cancel.stdout.contains("--job-id"))
    }

    @Test
    fun `agent start parses poll-interval flag`() {
        val cmd = AgentStartCommand()
        val result = cmd.test("--poll-interval 15 --help")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `agent start recognizes orchestrator flag`() {
        val cmd = AgentStartCommand()
        val result = cmd.test("--orchestrator --help")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `ephemeral identity prefers explicit agent id and normalizes labels`() {
        val agentId = Uuid.random().toString()

        assertEquals(
            EphemeralAgentIdentity(agentId, listOf("android", "gpu")),
            resolveEphemeralAgentIdentity(
                agentId,
                "ignored",
                " android, gpu ",
            ) {
                error("configuration must not be loaded")
            },
        )
        assertEquals(
            listOf("default"),
            resolveEphemeralAgentIdentity(agentId, null, null) { null }.labels,
        )
    }

    @Test
    fun `ephemeral identity accepts token subject and rejects malformed tokens`() {
        val agentId = Uuid.random().toString()
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"$agentId"}""".toByteArray())

        assertEquals(
            agentId,
            resolveEphemeralAgentIdentity(null, "header.$payload.signature", null) {
                error("configuration must not be loaded")
            }.agentId,
        )
        listOf(
            "not-a-jwt",
            "header.invalid-base64.signature",
            "header.${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray())}",
        ).forEach { token ->
            assertFailsWith<IllegalArgumentException> {
                resolveEphemeralAgentIdentity(null, token, null) { null }
            }
        }
    }

    @Test
    fun `ephemeral identity falls back to persisted config and fails when absent`() {
        val config = AgentConfig(
            agentId = Uuid.random().toString(),
            token = "token",
            serverUrl = "https://bosca.example",
            labels = listOf("linux"),
        )

        assertEquals(
            EphemeralAgentIdentity(config.agentId, config.labels),
            resolveEphemeralAgentIdentity(null, null, "ignored") { config },
        )
        assertFailsWith<IllegalArgumentException> {
            resolveEphemeralAgentIdentity(null, null, null) { null }
        }
    }
}
