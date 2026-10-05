package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

/**
 * Lists all API tokens for the authenticated principal, showing
 * status, prefix, name, and last-used information.
 */
class TokenListCommand : TokenSubcommand(name = "list") {
    override fun help(context: Context) = "List API tokens for the authenticated principal"

    private val limit by option("--limit", help = "Maximum number of tokens to return")
        .int().default(25)

    override suspend fun execute(apiTokens: ApiTokens) {
        val tokens = apiTokens.list(limit = limit)
        if (tokens.isEmpty()) {
            echo("No API tokens found.")
            return
        }
        echo("%-6s %-12s %-30s %-8s %-20s".format("ID", "PREFIX", "NAME", "ACTIVE", "LAST USED"))
        echo("-".repeat(80))
        for (t in tokens) {
            val lastUsed = t.lastUsedAt?.toString() ?: "never"
            echo("%-6d %-12s %-30s %-8s %-20s".format(
                t.id,
                t.tokenPrefix,
                t.name.take(30),
                if (t.active) "yes" else "no",
                lastUsed.take(20),
            ))
        }
    }
}
