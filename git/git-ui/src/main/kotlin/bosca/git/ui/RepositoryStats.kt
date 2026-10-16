package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.GitUiStats

/** Loads history-based statistics for the private deferred repository summary tiles. */
suspend fun repositoryStats(id: String): List<RepositoryStatistic> {
    val stats = currentRenderContext().gql.execute(GitUiStats, GitUiStats.Variables(id)).git.stats
    return listOf(
        RepositoryStatistic("Commits", stats.commitCount.toString()),
        RepositoryStatistic("Branches", stats.branchCount.toString()),
        RepositoryStatistic("Tags", stats.tagCount.toString()),
        RepositoryStatistic("Contributors", stats.contributorCount.toString()),
        RepositoryStatistic("Size", formatSize(stats.diskSizeBytes)),
    )
}
