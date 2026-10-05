package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context

/**
 * Lists all available API token scopes that can be assigned when
 * creating or editing tokens.
 */
class TokenScopesCommand : TokenSubcommand(name = "scopes") {
    override fun help(context: Context) = "List all available API token scopes"

    override suspend fun execute(apiTokens: ApiTokens) {
        val scopes = apiTokens.availableScopes()
        if (scopes.isEmpty()) {
            echo("No scopes available.")
            return
        }
        echo("%-30s %s".format("SCOPE", "DESCRIPTION"))
        echo("-".repeat(70))
        for (scope in scopes) {
            echo("%-30s %s".format(scope.name, scope.description))
        }
    }
}
