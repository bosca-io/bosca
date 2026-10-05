package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.long

/**
 * Revokes an API token by its credential ID, immediately
 * preventing further authentication with that token.
 */
class TokenRevokeCommand : TokenSubcommand(name = "revoke") {
    override fun help(context: Context) = "Revoke an API token by ID"

    private val id by argument(help = "Credential ID of the token to revoke").long()

    override suspend fun execute(apiTokens: ApiTokens) {
        val success = apiTokens.revoke(id)
        if (success) {
            echo("Token $id revoked successfully.")
        } else {
            echo("Failed to revoke token $id.", err = true)
        }
    }
}
