package bosca.cli.git

import bosca.cli.api.ApiTokens
import bosca.cli.config.DeploymentHosts
import bosca.cli.tokens.TokenSubcommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option

/**
 * `bosca git login` — explicitly provisions a git credential for a host
 * and wires the Bosca credential helper into git's global config.
 *
 * Most users never need this: `bosca git clone` provisions and wires up
 * automatically. It exists for setting up a host you push to before
 * cloning, or for rotating the cached token (each run mints a fresh one).
 *
 * The minted token is git-scoped (`git:read` + `git:write`) so it covers
 * clone, fetch, push, and pull. Afterwards plain `git` operations against
 * the host authenticate without any further action.
 */
class GitLoginCommand : TokenSubcommand(name = "login") {
    override fun help(context: Context) =
        "Provision a git credential for the Bosca git server and wire it into git"

    private val hostOption by option(
        "--host", "-h",
        envvar = "BOSCA_GIT_HOST",
        help = "Git server hostname (default: git.<domain> of the Bosca endpoint)",
    )

    private val tokenName by option(
        "--token-name",
        help = "Name for the generated API token",
    ).default("git-login")

    override suspend fun execute(apiTokens: ApiTokens) {
        val host = hostOption ?: DeploymentHosts.gitHost(endpoint)
        echo("Provisioning git credential for $host …")

        val result = apiTokens.create(
            name = tokenName,
            description = "Git credential for $host",
            scopes = GitCredentialHelper.SCOPES,
        )
        val tokenId = result.apiToken.id
        echo("Token created: ${result.apiToken.tokenPrefix}… (id $tokenId)")

        // Cache the token and configure git to consult our helper for this
        // host — so clone/fetch/push/pull all authenticate automatically.
        GitCredentialStore.put(host, result.rawToken)
        val installed = GitCredentialHelper.install(host) { echo("Warning: $it", err = true) }

        echo("")
        echo("Git credentials configured for $host")
        if (!installed) {
            echo("Note: global git config could not be updated; 'bosca git' commands still work.", err = true)
        }
        echo("Token ID: $tokenId (revoke with: bosca tokens revoke $tokenId)")
    }
}
