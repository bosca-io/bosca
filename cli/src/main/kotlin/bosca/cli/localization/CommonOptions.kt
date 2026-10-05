@file:OptIn(ExperimentalUuidApi::class)

package bosca.cli.localization

import bosca.cli.BoscaCliCommand
import bosca.graphql.client.GraphQLClient
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Options shared by every subcommand: where to talk to, with what credentials,
 * and which project to target. Extracted into a base class so each subcommand
 * stays focused on its own logic.
 *
 * `--project` is parsed to a [Uuid] at CLI-parse time, so every subcommand works
 * with a typed identifier rather than an unvalidated string — malformed UUIDs
 * fail before any network or disk I/O happens.
 */
abstract class LocalizationSubcommand(name: String, help: String) : BoscaCliCommand(name = name) {

    override fun help(context: com.github.ajalt.clikt.core.Context): String = helpText

    private val helpText: String = help

    protected val endpoint by option(
        "--endpoint",
        envvar = "BOSCA_ENDPOINT",
        help = "The bosca-server GraphQL endpoint (e.g. https://api.example.com/graphql)"
    )

    protected val token by option(
        "--token",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication"
    )

    protected val project: Uuid by option(
        "--project",
        help = "Target project identifier (UUID)"
    ).convert { Uuid.parse(it) }.required()

    protected suspend fun client(): GraphQLClient =
        networkClient(
            endpoint = endpoint,
            token = token,
            requireAuthentication = false,
        ).boscaGraphql
}
