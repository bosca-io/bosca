package bosca.cli.workops

import bosca.cli.BoscaCliCommand
import bosca.cli.api.WorkOpsApi
import bosca.cli.api.BoscaHttpException
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/**
 * Base class for workops subcommands providing shared authentication
 * and a resolved [WorkOpsApi] client instance.
 */
abstract class WorkOpsSubcommand(name: String) : BoscaCliCommand(name = name) {

    protected val url by option(
        "--url", "-u",
        envvar = "BOSCA_ENDPOINT",
        help = "Bosca GraphQL endpoint URL"
    )

    protected val token by option(
        "--token", "-t",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication"
    )

    protected val username by option(
        "--username",
        envvar = "BOSCA_USERNAME",
        help = "Authentication username"
    )

    protected val password by option(
        "--password",
        envvar = "BOSCA_PASSWORD",
        help = "Authentication password"
    )

    override fun run() = runBlocking {
        val network = networkClient(url, token, username, password)
        val api = WorkOpsApi(network)
        try {
            execute(api)
        } catch (e: BoscaHttpException) {
            when (e.statusCode) {
                401 -> echo("Error: authentication failed (401). Run 'bosca login' to re-authenticate.", err = true)
                403 -> echo("Error: permission denied (403).", err = true)
                else -> echo("Error: HTTP ${e.statusCode} — ${e.message}", err = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            echo("Error: ${e.message}", err = true)
        }
    }

    protected abstract suspend fun execute(api: WorkOpsApi)
}
