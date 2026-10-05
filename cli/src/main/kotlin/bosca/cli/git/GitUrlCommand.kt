package bosca.cli.git

import bosca.cli.api.GitApi
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument

/**
 * `bosca git url <owner/repo>` — prints just the clone URL, suitable for
 * scripting (e.g. `git clone "$(bosca git url acme/widgets)"`).
 */
class GitUrlCommand : GitSubcommand("url") {
    override fun help(context: Context) = "Print the clone URL for a repository"

    private val ref by argument(
        "repository",
        help = "Repository as owner/repo (e.g. acme/widgets)",
    )

    override suspend fun execute(api: GitApi) {
        val parsed = GitUrls.parseRef(ref)
        val repo = api.getRepository(parsed.owner, parsed.repo) ?: run {
            echo("Error: repository '$parsed' not found or not accessible", err = true)
            return
        }
        echo(repo.cloneUrl)
    }
}
