package bosca.git.ui

import bosca.bml.graphql.execute
import bosca.bml.render.currentRenderContext
import bosca.git.ui.graphql.GitUiStats

/** Loads history-based statistics for the private deferred repository summary. */
suspend fun repositoryStats(id: String): String {
    val stats = currentRenderContext().gql.execute(GitUiStats, GitUiStats.Variables(id)).git.stats
    return listOf(
        Triple(stats.commitCount, "commit", "commits"),
        Triple(stats.branchCount, "branch", "branches"),
        Triple(stats.tagCount, "tag", "tags"),
        Triple(stats.contributorCount, "contributor", "contributors"),
    ).joinToString(" · ") { (count, singular, plural) ->
        "$count " + if (count.toLong() == 1L) singular else plural
    } + " · ${formatSize(stats.diskSizeBytes)}"
}
