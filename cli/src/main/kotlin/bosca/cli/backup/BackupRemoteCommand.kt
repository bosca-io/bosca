package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import bosca.cli.api.BackupApi
import bosca.cli.api.BoscaHttpException
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/** Shares profile authentication and failure handling for backup administration queries. */
abstract class BackupRemoteCommand(name: String) : BoscaCliCommand(name = name) {
    private val url by option("--url", "-u", envvar = "BOSCA_ENDPOINT", help = "Bosca GraphQL endpoint URL")
    private val token by option("--token", "-t", envvar = "BOSCA_TOKEN", help = "Bearer token for authentication")
    private val username by option("--username", envvar = "BOSCA_USERNAME", help = "Authentication username")
    private val password by option("--password", envvar = "BOSCA_PASSWORD", help = "Authentication password")
    protected val jsonOutput by option("--json", help = "Emit structured request state as JSON").flag()

    final override fun run() = runBlocking {
        try {
            execute(BackupApi(networkClient(url, token, username, password)))
            echo("Request completion does not establish full recovery coverage.", err = true)
        } catch (failure: BoscaHttpException) {
            throw CliktError(when (failure.statusCode) {
                401 -> "Authentication failed (401). Run 'bosca login' to re-authenticate."
                403 -> "Permission denied (403)."
                else -> "HTTP ${failure.statusCode} — ${failure.message}"
            })
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: CliktError) {
            throw failure
        } catch (failure: Exception) {
            throw CliktError(failure.message ?: "Backup query failed")
        }
    }

    /** Executes one read under the selected profile and the server's administration boundary. */
    protected abstract suspend fun execute(api: BackupApi)
}
