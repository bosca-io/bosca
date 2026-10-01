package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.long

/**
 * Retrieves and displays detailed information about a single
 * API token identified by its credential ID.
 */
class TokenGetCommand : TokenSubcommand(name = "get") {
    override fun help(context: Context) = "Show details of a specific API token"

    private val id by argument(help = "Credential ID of the token").long()

    override suspend fun execute(apiTokens: ApiTokens) {
        val token = apiTokens.get(id)
        if (token == null) {
            echo("Token $id not found.", err = true)
            return
        }
        echo("ID:            ${token.id}")
        echo("Name:          ${token.name}")
        echo("Description:   ${token.description ?: "-"}")
        echo("Prefix:        ${token.tokenPrefix}")
        echo("Active:        ${token.active}")
        echo("Principal:     ${token.principalId}")
        echo("Scopes:        ${token.scopes?.joinToString(", ") ?: "unrestricted"}")
        echo("Expires:       ${token.expiresAt ?: "never"}")
        echo("Last Used:     ${token.lastUsedAt ?: "never"}")
        echo("Last Used IP:  ${token.lastUsedIp ?: "-"}")
        echo("Revoked:       ${token.revokedAt ?: "no"}")
    }
}
