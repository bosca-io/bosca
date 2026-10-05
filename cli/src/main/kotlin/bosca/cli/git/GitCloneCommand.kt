package bosca.cli.git

import bosca.cli.api.GitApi
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple

/**
 * `bosca git clone <owner/repo> [dir] [git-options]` — the headline
 * convenience command.
 *
 * It resolves the clone URL from the server (so you reference a repo by
 * `owner/repo`, not a URL), wires the credential helper into git config
 * for the host, provisions a token if one isn't cached, then hands off to
 * `git clone`. After this, every future git operation against that host —
 * including plain `git pull`/`git push` inside the clone — authenticates
 * automatically.
 *
 * A full clone URL is also accepted, in which case the GraphQL lookup is
 * skipped and the URL is cloned directly (still with auth configured).
 */
class GitCloneCommand : GitSubcommand("clone") {
    override fun help(context: Context) =
        "Clone a repository from the Bosca git server, configuring authentication automatically"

    // Accept arbitrary `git clone` flags (e.g. --depth) as pass-through.
    override val treatUnknownOptionsAsArgs = true

    private val ref by argument(
        "repository",
        help = "Repository as owner/repo (e.g. acme/widgets), or a full clone URL",
    )

    private val rest by argument(
        "args",
        help = "Optional target directory followed by any extra git clone options",
    ).multiple()

    override suspend fun execute(api: GitApi) {
        if (!GitCli.isAvailable()) {
            echo("Error: git is not installed or not on PATH", err = true)
            throw ProgramResult(127)
        }

        val cloneUrl = resolveCloneUrl(api) ?: return

        val configArgs = mutableListOf<String>()
        val host = GitUrls.host(cloneUrl)
        if (host != null) {
            // Persist the helper for all future operations against this host…
            GitCredentialHelper.install(host) { echo("Warning: $it", err = true) }
            // …and pre-provision a token so this clone authenticates with
            // whatever auth the command used (token/username/stored session).
            GitCredentialHelper.ensureCredential(host, network) { echo("Warning: $it", err = true) }
            // Also inject the helper for this single invocation, so the clone
            // succeeds even if the global install above could not be written.
            configArgs += GitCredentialHelper.invocationConfigArgs(host)
        }

        val command = buildList {
            addAll(configArgs)
            add("clone")
            add(cloneUrl)
            addAll(rest)
        }
        echo("Cloning $cloneUrl …")
        val code = GitCli.inherit(*command.toTypedArray())
        if (code != 0) {
            echo("Error: git clone failed (exit code $code)", err = true)
            throw ProgramResult(code)
        }
    }

    private suspend fun resolveCloneUrl(api: GitApi): String? {
        if (GitUrls.looksLikeUrl(ref)) return ref
        val parsed = GitUrls.parseRef(ref)
        val repo = api.getRepository(parsed.owner, parsed.repo)
        if (repo == null) {
            echo("Error: repository '$parsed' not found or not accessible", err = true)
            return null
        }
        return repo.cloneUrl
    }
}
