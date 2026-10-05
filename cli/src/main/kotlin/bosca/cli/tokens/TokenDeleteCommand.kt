package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.long

/**
 * Permanently deletes a revoked API token record. The token
 * must already be revoked before it can be deleted.
 */
class TokenDeleteCommand : TokenSubcommand(name = "delete") {
    override fun help(context: Context) = "Permanently delete a revoked API token"

    private val id by argument(help = "Credential ID of the revoked token to delete").long()

    override suspend fun execute(apiTokens: ApiTokens) {
        val success = apiTokens.delete(id)
        if (success) {
            echo("Token $id deleted permanently.")
        } else {
            echo("Failed to delete token $id. Is it revoked?", err = true)
        }
    }
}
