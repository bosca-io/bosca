package bosca.cli.git

import bosca.cli.BoscaCliCommand
import bosca.cli.api.GitApi
import bosca.cli.api.NetworkClient
import bosca.cli.api.BoscaHttpException
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/**
 * Base class for git subcommands that talk to the GraphQL API
 * (list/info/url/clone/pr). Resolves credentials in the same priority
 * order as every other Bosca command — explicit flags, environment
 * variables, then the stored `bosca login` session — and exposes the
 * authenticated [network] so credential-provisioning subcommands can
 * mint git tokens with whatever auth the user supplied.
 */
abstract class GitSubcommand(name: String) : BoscaCliCommand(name = name) {

    protected val url by option(
        "--url", "-u",
        envvar = "BOSCA_ENDPOINT",
        help = "Bosca GraphQL endpoint URL",
    )

    protected val token by option(
        "--token", "-t",
        envvar = "BOSCA_TOKEN",
        help = "Bearer token for authentication (skips login)",
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

    /** The authenticated client, available to [execute]. */
    protected lateinit var network: NetworkClient
        private set

    override fun run() = runBlocking {
        network = networkClient(url, token, username, password)

        try {
            execute(GitApi(network))
        } catch (e: CliktError) {
            // Clikt control-flow (ProgramResult exit codes, usage errors) —
            // let the framework handle it rather than masking as an error.
            throw e
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

    protected abstract suspend fun execute(api: GitApi)

    /**
     * Resolves an `owner/repo` reference to its repository, printing a
     * not-found error and returning null when it cannot be accessed.
     * Shared by the commands that take a repo reference but need the
     * repository's UUID (e.g. the pull-request commands).
     */
    protected suspend fun resolveRepository(
        api: GitApi,
        ref: String,
    ): bosca.graphql.gen.GetGitRepositoryData.Git.Repository? {
        val parsed = GitUrls.parseRef(ref)
        val repo = api.getRepository(parsed.owner, parsed.repo)
        if (repo == null) {
            echo("Error: repository '$parsed' not found or not accessible", err = true)
        }
        return repo
    }
}
