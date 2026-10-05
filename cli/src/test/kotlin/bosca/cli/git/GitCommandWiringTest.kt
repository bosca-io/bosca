package bosca.cli.git

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitCommandWiringTest {

    private fun gitCommand() = GitCommand().subcommands(
        GitLoginCommand(),
        GitCloneCommand(),
        GitListCommand(),
        GitInfoCommand(),
        GitUrlCommand(),
        GitPushCommand(),
        GitPullCommand(),
        GitFetchCommand(),
        GitMergeCommand(),
        GitCredentialHelperCommand(),
        GitPrCommand().subcommands(
            GitPrListCommand(),
            GitPrViewCommand(),
            GitPrCreateCommand(),
            GitPrMergeCommand(),
        ),
    )

    @Test
    fun `git command lists its subcommands in help`() {
        val result = gitCommand().test("--help")
        assertEquals(0, result.statusCode)
        for (name in listOf("login", "clone", "list", "info", "url", "push", "pull", "fetch", "merge", "pr")) {
            assertTrue(result.stdout.contains(name), "Expected '$name' in help: ${result.stdout}")
        }
    }

    @Test
    fun `internal credential-helper is hidden from help`() {
        val result = gitCommand().test("--help")
        assertFalse(
            result.stdout.contains("credential-helper"),
            "credential-helper should be hidden: ${result.stdout}",
        )
    }

    @Test
    fun `pr command lists its subcommands in help`() {
        val result = GitPrCommand().subcommands(
            GitPrListCommand(),
            GitPrViewCommand(),
            GitPrCreateCommand(),
            GitPrMergeCommand(),
        ).test("--help")
        assertEquals(0, result.statusCode)
        for (name in listOf("list", "view", "create", "merge")) {
            assertTrue(result.stdout.contains(name), "Expected '$name' in pr help: ${result.stdout}")
        }
    }

    @Test
    fun `passthrough and clone commands forward unknown options to git`() {
        assertTrue(GitPushCommand().treatUnknownOptionsAsArgs)
        assertTrue(GitPullCommand().treatUnknownOptionsAsArgs)
        assertTrue(GitFetchCommand().treatUnknownOptionsAsArgs)
        assertTrue(GitMergeCommand().treatUnknownOptionsAsArgs)
        assertTrue(GitCloneCommand().treatUnknownOptionsAsArgs)
    }

    @Test
    fun `credential-helper command is marked hidden`() {
        assertTrue(GitCredentialHelperCommand().hiddenFromHelp)
    }
}
