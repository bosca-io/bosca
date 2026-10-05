package bosca.cli.tokens

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/**
 * Parent command grouping all API token management subcommands.
 */
class TokenCommand : BoscaCliCommand(name = "tokens") {
    override fun help(context: Context) = "Manage API tokens for programmatic access"
    override fun run() = Unit
}
