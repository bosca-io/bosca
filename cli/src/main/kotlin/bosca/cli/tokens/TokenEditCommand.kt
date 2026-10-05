package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long

/**
 * Updates the name, description, or scopes of an existing API token.
 * Only the fields provided are modified; omitted fields retain their
 * current values.
 */
class TokenEditCommand : TokenSubcommand(name = "edit") {
    override fun help(context: Context) = "Update an API token's name, description, or scopes"

    private val id by argument(help = "Credential ID of the token to edit").long()

    private val name by option("--name", "-n", help = "New name for the token")

    private val description by option("--description", "-d", help = "New description for the token")

    private val scopes by option("--scope", "-s", help = "New permission scope (repeatable, replaces existing scopes)")
        .multiple()

    override suspend fun execute(apiTokens: ApiTokens) {
        if (name == null && description == null && scopes.isEmpty()) {
            echo("Error: at least one of --name, --description, or --scope is required", err = true)
            return
        }
        val updated = apiTokens.edit(
            id = id,
            name = name,
            description = description,
            scopes = scopes.ifEmpty { null },
        )
        echo("Token $id updated.")
        echo("  Name:   ${updated.name}")
        echo("  Scopes: ${updated.scopes?.joinToString(", ") ?: "unrestricted"}")
    }
}
