package bosca.cli.git

import bosca.cli.api.GitApi
import bosca.cli.api.Profiles
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.uuid.Uuid

/**
 * `bosca git list` — lists the repositories the authenticated user can
 * access, with optional filters. The clone URL is shown so it can be fed
 * straight into `bosca git clone` (or copied for any other tool).
 */
class GitListCommand : GitSubcommand("list") {
    override fun help(context: Context) = "List git repositories you can access"

    private val contentType by option(
        "--content-type", "-c",
        help = "Filter by content type (${GitFormat.contentTypeNames().joinToString(", ")})",
    )

    private val includeArchived by option(
        "--include-archived",
        help = "Include archived repositories",
    ).flag()

    private val ownerId by option(
        "--owner-id",
        help = "Filter by owner profile/organization UUID",
    )

    override suspend fun execute(api: GitApi) {
        val type = contentType?.let { GitFormat.parseContentType(it) }
        val owner = ownerId?.let { Uuid.parse(it) }

        val repos = api.listRepositories(
            contentType = type,
            includeArchived = includeArchived,
            ownerId = owner,
        )
        if (repos.isEmpty()) {
            echo("No repositories found.")
            return
        }

        // Resolve each distinct owner id to its slug through GraphQL. Owners the
        // caller can't see resolve to null and render as "-".
        val profiles = Profiles(network)
        val ownerSlugs = repos.map { it.ownerId }.distinct().associateWith { profiles.getSlug(it) }

        val format = "%-20s  %-24s  %-10s  %-12s  %-16s  %s"
        echo(format.format("OWNER", "SLUG", "VISIBILITY", "BRANCH", "TYPE", "CLONE URL"))
        echo("-".repeat(118))
        val rows = repos
            .map { repo -> repo to (ownerSlugs[repo.ownerId] ?: "-") }
            .sortedWith(compareBy({ it.second }, { it.first.slug }))
        for ((repo, owner) in rows) {
            echo(
                format.format(
                    owner.take(20),
                    repo.slug.take(24),
                    repo.visibility.name.take(10),
                    repo.defaultBranch.take(12),
                    (repo.contentType?.name ?: "-").take(16),
                    repo.cloneUrl,
                )
            )
        }
    }
}
