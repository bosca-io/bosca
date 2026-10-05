package bosca.cli.git

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.coroutines.runBlocking

/**
 * Implements git's credential-helper protocol so that *every* git
 * operation against a Bosca host — clone, fetch, push, pull, even plain
 * `git` inside a cloned repo — authenticates automatically.
 *
 * git invokes this as `bosca git credential-helper <operation>`, writing
 * the request (`protocol=`, `host=`, …) to stdin:
 *
 *  - `get`   → look up / mint a token for the host and print credentials.
 *  - `store` → cache the credential git just used successfully.
 *  - `erase` → drop the cached token (git calls this when a token is
 *              rejected, so the next `get` mints a fresh one).
 *
 * Users never run this directly; [GitCredentialHelper.install] wires it
 * into git's per-host config from `clone`/`login`.
 */
class GitCredentialHelperCommand : BoscaCliCommand(name = "credential-helper") {
    override fun help(context: Context) =
        "(internal) git credential helper used by Bosca git commands; not intended to be run directly"

    override val hiddenFromHelp = true

    private val operation by argument(
        "operation",
        help = "git credential action: get, store, or erase",
    )

    override fun run() = runBlocking {
        val request = GitCredentialHelper.parseInput(System.`in`.bufferedReader().readText())
        val host = GitCredentialHelper.hostOf(request)
        when (operation) {
            "get" -> {
                if (host == null) return@runBlocking
                val token = GitCredentialHelper.resolveToken(host, request["path"]) ?: run {
                    // No credential available — stay silent so git can fall
                    // back to other helpers or prompt. Hint on stderr only.
                    echo(
                        "bosca: no unambiguous credential for $host " +
                            "(select a profile or run 'bosca git login')",
                        err = true,
                    )
                    return@runBlocking
                }
                echo(
                    GitCredentialHelper.formatCredential(GitCredentialHelper.USERNAME, token),
                    trailingNewline = false,
                )
            }
            "store" -> {
                val password = request["password"]
                if (host != null && password != null) {
                    GitCredentialStore.putFromHelper(host, password, path = request["path"])
                }
            }
            "erase" -> {
                if (host != null) {
                    GitCredentialStore.removeFromHelper(host, request["password"], path = request["path"])
                }
            }
            // Unknown operations are ignored per the git credential spec.
        }
    }
}
