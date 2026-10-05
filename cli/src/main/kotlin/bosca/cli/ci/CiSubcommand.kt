package bosca.cli.ci

import bosca.cli.BoscaCliCommand
import bosca.cli.api.BoscaHttpException
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

abstract class CiSubcommand(name: String) : BoscaCliCommand(name = name) {

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
        // BoscaAuth's per-request token provider lazily refreshes the access token
        // for the life of this (possibly long-running) command and persists each
        // rotation to the CLI config, so no background refresh loop is needed.
        val network = networkClient(url, token, username, password)
        val api = CiApi(network)
        try {
            execute(api)
        } catch (e: CliktError) {
            throw e
        } catch (e: BoscaHttpException) {
            when (e.statusCode) {
                401 -> echo("Error: authentication failed (401). Run 'bosca login' to re-authenticate.", err = true)
                403 -> echo("Error: permission denied (403).", err = true)
                else -> echo("Error: HTTP ${e.statusCode} — ${e.message}", err = true)
            }
            throw ProgramResult(1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val chain = generateSequence(e as Throwable?) { it.cause }
                .map { "${it::class.simpleName}: ${it.message ?: "<no message>"}" }
                .joinToString(" -> ")
            echo("Error: $chain", err = true)
            throw ProgramResult(1)
        }
    }

    protected abstract suspend fun execute(api: CiApi)
}
