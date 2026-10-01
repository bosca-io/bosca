package bosca.cli.git

import bosca.cli.BoscaCliCommand
import bosca.cli.api.GitApi
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long

/** Parent of the `bosca git pr …` pull-request commands. */
class GitPrCommand : BoscaCliCommand(name = "pr") {
    override fun help(context: Context) = "Manage pull requests on Bosca repositories"
    override fun run() = Unit
}

/** `bosca git pr list <owner/repo>` — list pull requests. */
class GitPrListCommand : GitSubcommand("list") {
    override fun help(context: Context) = "List pull requests for a repository"

    private val ref by argument("repository", help = "Repository as owner/repo")
    private val status by option(
        "--status", "-s",
        help = "Filter by status (${GitFormat.pullRequestStatusNames().joinToString(", ")})",
    )
    private val limit by option("--limit", help = "Maximum results").int().default(50)
    private val offset by option("--offset", help = "Result offset").long().default(0)

    override suspend fun execute(api: GitApi) {
        val repo = resolveRepository(api, ref) ?: return
        val filter = status?.let { GitFormat.parsePullRequestStatus(it) }
        val prs = api.listPullRequests(repo.id, status = filter, limit = limit, offset = offset)
        if (prs.isEmpty()) {
            echo("No pull requests found.")
            return
        }

        val format = "%-7s  %-8s  %-28s  %-9s  %s"
        echo(format.format("NUMBER", "STATUS", "BRANCHES", "MERGEABLE", "TITLE"))
        echo("-".repeat(96))
        for (pr in prs) {
            echo(
                format.format(
                    "#${pr.number}",
                    pr.status.name.take(8),
                    "${pr.sourceBranch} → ${pr.targetBranch}".take(28),
                    if (pr.mergeable) "yes" else "no",
                    pr.title.take(40),
                )
            )
        }
    }
}

/** `bosca git pr view <owner/repo> <number>` — show a PR in detail. */
class GitPrViewCommand : GitSubcommand("view") {
    override fun help(context: Context) = "Show a pull request in detail"

    private val ref by argument("repository", help = "Repository as owner/repo")
    private val number by argument("number", help = "Pull request number").int()

    override suspend fun execute(api: GitApi) {
        val repo = resolveRepository(api, ref) ?: return
        val pr = api.getPullRequest(repo.id, number) ?: run {
            echo("Error: pull request #$number not found", err = true)
            return
        }

        echo("PR #${pr.number}: ${pr.title}")
        echo("Status:     ${pr.status.name}")
        echo("Branches:   ${pr.sourceBranch} → ${pr.targetBranch}")
        echo("Author:     ${pr.authorId}")
        echo("Mergeable:  ${pr.mergeable}")
        if (pr.conflictingFiles.isNotEmpty()) {
            echo("Conflicts:  ${pr.conflictingFiles.joinToString(", ")}")
        }
        pr.mergeStrategy?.let { echo("Merged via: ${it.name}") }
        pr.mergeSha?.let { echo("Merge SHA:  $it") }
        pr.mergedAt?.let { echo("Merged at:  $it") }
        echo("Created:    ${pr.created}")
        echo("Updated:    ${pr.updated}")
        pr.description?.takeIf { it.isNotBlank() }?.let {
            echo("")
            echo(it)
        }
    }
}

/** `bosca git pr create <owner/repo>` — open a new pull request. */
class GitPrCreateCommand : GitSubcommand("create") {
    override fun help(context: Context) = "Open a new pull request"

    private val ref by argument("repository", help = "Repository as owner/repo")
    private val title by option("--title", help = "Pull request title").required()
    private val source by option("--source", help = "Source branch (the branch with your changes)").required()
    private val target by option(
        "--target",
        help = "Target branch (defaults to the repository's default branch)",
    )
    private val description by option("--description", "-d", help = "Markdown description")
    private val draft by option("--draft", help = "Open as a draft pull request").flag()

    override suspend fun execute(api: GitApi) {
        val repo = resolveRepository(api, ref) ?: return
        val targetBranch = target ?: repo.defaultBranch
        val pr = api.createPullRequest(
            repositoryId = repo.id,
            title = title,
            sourceBranch = source,
            targetBranch = targetBranch,
            description = description,
            isDraft = draft,
        )
        echo("Created PR #${pr.number}: ${pr.title}")
        echo("  ${pr.sourceBranch} → ${pr.targetBranch}  [${pr.status.name}]")
    }
}

/** `bosca git pr merge <owner/repo> <number>` — merge a pull request. */
class GitPrMergeCommand : GitSubcommand("merge") {
    override fun help(context: Context) = "Merge a pull request"

    private val ref by argument("repository", help = "Repository as owner/repo")
    private val number by argument("number", help = "Pull request number").int()
    private val strategy by option(
        "--strategy",
        help = "Merge strategy (${GitFormat.mergeStrategyNames().joinToString(", ")})",
    ).default("MERGE_COMMIT")

    override suspend fun execute(api: GitApi) {
        val repo = resolveRepository(api, ref) ?: return
        val pr = api.getPullRequest(repo.id, number) ?: run {
            echo("Error: pull request #$number not found", err = true)
            return
        }
        val mergeStrategy = GitFormat.parseMergeStrategy(strategy)
        val merged = api.mergePullRequest(pr.id, mergeStrategy)
        echo("Merged PR #${merged.number} using ${mergeStrategy.name}")
        merged.mergeSha?.let { echo("  merge commit: $it") }
    }
}
