package bosca.cli.tokens

import bosca.cli.api.ApiTokens
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Creates a new API token and prints the raw token value to stdout.
 * The raw token is only shown once — subsequent retrievals only
 * expose the prefix for identification.
 */
class TokenCreateCommand : TokenSubcommand(name = "create") {
    override fun help(context: Context) = "Create a new API token"

    private val name by option("--name", "-n", help = "Human-readable name for the token")
        .required()

    private val description by option("--description", "-d", help = "Optional description of the token's purpose")

    private val scopes by option("--scope", "-s", help = "Permission scope (repeatable, e.g. content:view)")
        .multiple()

    private val expiresAt by option("--expires", help = "Expiration timestamp in ISO-8601 format")

    override suspend fun execute(apiTokens: ApiTokens) {
        val expiration = expiresAt?.let {
            try {
                ZonedDateTime.parse(it)
            } catch (_: DateTimeParseException) {
                echo("Error: invalid --expires format, expected ISO-8601 (e.g. 2025-12-31T23:59:59Z)", err = true)
                return
            }
        }

        val result = apiTokens.create(
            name = name,
            description = description,
            scopes = scopes.ifEmpty { null },
            expiresAt = expiration,
        )

        echo("Token created successfully.")
        echo("")
        echo("  ID:     ${result.apiToken.id}")
        echo("  Name:   ${result.apiToken.name}")
        echo("  Prefix: ${result.apiToken.tokenPrefix}")
        echo("")
        echo("  Raw Token: ${result.rawToken}")
        echo("")
        echo("  Store this token securely — it cannot be retrieved again.")
    }
}
