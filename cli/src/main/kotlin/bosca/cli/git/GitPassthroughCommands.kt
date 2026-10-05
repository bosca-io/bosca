package bosca.cli.git

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

/**
 * Base for thin wrappers around a local `git` subcommand (push, pull,
 * fetch, merge). They forward every argument straight through to git,
 * but first make sure the Bosca credential helper is wired up for the
 * current repository's `origin` host — so the operation authenticates
 * even when the repo was not cloned via `bosca git clone`.
 *
 * Once a repo has been Bosca-cloned the global helper already covers it,
 * making these wrappers optional sugar; their value is letting you stay
 * in the `bosca git …` habit and auto-wiring foreign checkouts.
 */
abstract class GitPassthroughCommand(
    name: String,
    private val gitOp: String,
    private val summary: String,
) : BoscaCliCommand(name = name) {

    override fun help(context: Context) = summary

    // Forward unknown flags (e.g. --force, --rebase) verbatim to git.
    override val treatUnknownOptionsAsArgs = true

    private val args by argument(
        "git-args",
        help = "Arguments forwarded to 'git $gitOp'",
    ).multiple()

    override fun run() {
        if (!GitCli.isAvailable()) {
            echo("Error: git is not installed or not on PATH", err = true)
            throw ProgramResult(127)
        }
        val configArgs = originCredentialConfig()
        val command = configArgs + gitOp + args
        val code = GitCli.inherit(*command.toTypedArray())
        if (code != 0) throw ProgramResult(code)
    }

    private fun originCredentialConfig(): List<String> {
        val remote = GitCli.capture("remote", "get-url", "origin")
        if (!remote.isSuccess) return emptyList()
        val host = GitUrls.host(remote.stdout) ?: return emptyList()
        GitCredentialHelper.install(host)
        return GitCredentialHelper.invocationConfigArgs(host)
    }
}

class GitPushCommand : GitPassthroughCommand(
    "push", "push",
    "Push to a Bosca repository (credentials handled automatically)",
)

class GitPullCommand : GitPassthroughCommand(
    "pull", "pull",
    "Pull from a Bosca repository (credentials handled automatically)",
)

class GitFetchCommand : GitPassthroughCommand(
    "fetch", "fetch",
    "Fetch from a Bosca repository (credentials handled automatically)",
)

class GitMergeCommand : GitPassthroughCommand(
    "merge", "merge",
    "Merge branches in the current repository",
)
