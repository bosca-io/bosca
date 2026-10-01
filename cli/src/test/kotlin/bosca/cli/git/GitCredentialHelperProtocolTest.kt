package bosca.cli.git

import bosca.cli.config.CliInvocation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitCredentialHelperProtocolTest {

    @AfterTest
    fun resetProfile() {
        CliInvocation.selectProfile(null)
    }

    @Test
    fun `parseInput reads key-value lines up to the terminating blank line`() {
        val parsed = GitCredentialHelper.parseInput(
            "protocol=https\nhost=git.acme.io\nusername=api_token\n\nignored=after-blank"
        )
        assertEquals(
            mapOf("protocol" to "https", "host" to "git.acme.io", "username" to "api_token"),
            parsed,
        )
    }

    @Test
    fun `parseInput tolerates CRLF line endings`() {
        val parsed = GitCredentialHelper.parseInput("protocol=https\r\nhost=git.acme.io\r\n")
        assertEquals("git.acme.io", parsed["host"])
    }

    @Test
    fun `parseInput ignores lines without a key`() {
        val parsed = GitCredentialHelper.parseInput("=novalue\nnoequals\nhost=git.acme.io\n")
        assertEquals(mapOf("host" to "git.acme.io"), parsed)
    }

    @Test
    fun `formatCredential emits the git protocol reply`() {
        assertEquals(
            "username=api_token\npassword=secret\n\n",
            GitCredentialHelper.formatCredential("api_token", "secret"),
        )
    }

    @Test
    fun `hostOf returns the host or null when absent or blank`() {
        assertEquals("git.acme.io", GitCredentialHelper.hostOf(mapOf("host" to "git.acme.io")))
        assertNull(GitCredentialHelper.hostOf(emptyMap()))
        assertNull(GitCredentialHelper.hostOf(mapOf("host" to "")))
    }

    @Test
    fun `config helpers scope to the host and re-invoke this CLI`() {
        assertEquals("credential.https://git.acme.io.helper", GitCredentialHelper.configKey("git.acme.io"))
        assertEquals(
            "credential.https://git.acme.io.useHttpPath",
            GitCredentialHelper.useHttpPathConfigKey("git.acme.io"),
        )

        val value = GitCredentialHelper.configValue()
        assertTrue(value.startsWith("!"), "helper value must be a git shell command: $value")
        assertTrue(value.contains("git credential-helper"), "helper value must invoke the subcommand: $value")

        assertEquals(
            "${GitCredentialHelper.configKey("git.acme.io")}=${GitCredentialHelper.configValue()}",
            GitCredentialHelper.configArg("git.acme.io"),
        )
        assertEquals(
            "credential.https://git.acme.io.useHttpPath=true",
            GitCredentialHelper.useHttpPathConfigArg("git.acme.io"),
        )
        assertEquals(
            listOf(
                "-c",
                "credential.https://git.acme.io.helper=",
                "-c",
                "${GitCredentialHelper.configKey("git.acme.io")}=${GitCredentialHelper.configValue()}",
                "-c",
                "credential.https://git.acme.io.useHttpPath=true",
            ),
            GitCredentialHelper.invocationConfigArgs("git.acme.io"),
        )
    }

    @Test
    fun `temporary profile is embedded only in one-shot git helper configuration`() {
        CliInvocation.selectProfile("personal")

        val persistedValue = GitCredentialHelper.configValue()
        val oneShotValue = GitCredentialHelper.configArg("git.acme.io")

        assertFalse(persistedValue.contains("--profile"))
        assertTrue(persistedValue.contains("git credential-helper"))
        assertTrue(oneShotValue.contains("--profile \"personal\""))
        assertTrue(oneShotValue.contains("git credential-helper"))
    }

    @Test
    fun `default scopes cover read and write`() {
        assertEquals(listOf("git:read", "git:write"), GitCredentialHelper.SCOPES)
    }
}
