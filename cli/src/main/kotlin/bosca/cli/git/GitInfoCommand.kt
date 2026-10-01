package bosca.cli.git

import bosca.cli.api.GitApi
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument

/**
 * `bosca git info <owner/repo>` — shows the details of a single
 * repository, including the clone URL the credential helper will
 * authenticate against.
 */
class GitInfoCommand : GitSubcommand("info") {
    override fun help(context: Context) = "Show details for a single repository"

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

        echo("Repository:     ${parsed.owner}/${repo.slug}")
        echo("Name:           ${repo.name}")
        echo("ID:             ${repo.id}")
        echo("Visibility:     ${repo.visibility.name}")
        echo("Default branch: ${repo.defaultBranch}")
        echo("Content type:   ${repo.contentType?.name ?: "-"}")
        echo("Archived:       ${repo.archived}")
        echo("Disk size:      ${GitFormat.humanBytes(repo.diskSizeBytes)}")
        repo.description?.takeIf { it.isNotBlank() }?.let { echo("Description:    $it") }
        echo("Clone URL:      ${repo.cloneUrl}")
        echo("Created:        ${repo.created}")
        echo("Updated:        ${repo.updated}")
    }
}
