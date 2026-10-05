package bosca.cli.analytics

import bosca.cli.BoscaCliCommand
import bosca.cli.api.AnalyticsApi
import bosca.cli.api.BoscaHttpException
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

class AnalyticsCommand : BoscaCliCommand(name = "analytics") {
    override fun help(context: Context) =
        "Manage and execute analytics queries, visualizations, and dashboards"

    override fun run() = Unit
}

class AnalyticsQueryCommand : BoscaCliCommand(name = "query") {
    override fun help(context: Context) = "Manage saved analytics queries"
    override fun run() = Unit
}

class AnalyticsVisualizationCommand : BoscaCliCommand(name = "visualization") {
    override fun help(context: Context) = "Manage and render analytics visualizations"
    override fun run() = Unit
}

class AnalyticsDashboardCommand : BoscaCliCommand(name = "dashboard") {
    override fun help(context: Context) = "Manage and render analytics dashboards"
    override fun run() = Unit
}

class AnalyticsPermissionCommand : BoscaCliCommand(name = "permission") {
    override fun help(context: Context) = "Grant or revoke group permissions"
    override fun run() = Unit
}

abstract class AnalyticsSubcommand(name: String) : BoscaCliCommand(name = name) {

    protected val url by option(
        "--url", "-u",
        envvar = "BOSCA_ENDPOINT",
        help = "Bosca GraphQL endpoint URL",
    )

    protected val token by option(
        "--token", "-t",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication",
    )

    protected val username by option(
        "--username",
        envvar = "BOSCA_USERNAME",
        help = "Authentication username",
    )

    protected val password by option(
        "--password",
        envvar = "BOSCA_PASSWORD",
        help = "Authentication password",
    )

    final override fun run() = runBlocking {
        val network = networkClient(url, token, username, password)
        try {
            execute(AnalyticsApi(network))
        } catch (e: BoscaHttpException) {
            val message = when (e.statusCode) {
                401 -> "Authentication failed (401). Run 'bosca login' to re-authenticate."
                403 -> "Permission denied (403)."
                else -> "HTTP ${e.statusCode} — ${e.message}"
            }
            throw CliktError(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CliktError) {
            throw e
        } catch (e: Exception) {
            throw CliktError(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    protected abstract suspend fun execute(api: AnalyticsApi)
}
