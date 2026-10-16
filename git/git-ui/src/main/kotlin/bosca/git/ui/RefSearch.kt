package bosca.git.ui

import bml.generated.GitRefSearch
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.GitUiBranches
import bosca.git.ui.graphql.GitUiTags

class RefSearch : GitRefSearch {
    /** Loads one tag page through the authorized Git API, with branches on the first page. */
    override suspend fun search(
        gql: GraphQLClient, id: String, query: String, offset: Long, branchesOnly: Boolean,
    ): List<Map<String, String>> {
        require(offset >= 0) { "Offset must not be negative." }
        val search = query.trim()
        val qualifiedTag = search.startsWith("refs/tags/", ignoreCase = true)
        val qualifiedBranch = search.startsWith("refs/heads/", ignoreCase = true)
        val name = when {
            qualifiedTag -> search.substring("refs/tags/".length)
            qualifiedBranch -> search.substring("refs/heads/".length)
            else -> search
        }
        val branches = if (offset == 0L && !qualifiedTag) {
            gql.execute(GitUiBranches, GitUiBranches.Variables(id)).git.branches
                .filter { it.name.contains(name, ignoreCase = true) }
                .map { mapOf("name" to it.name, "kind" to "branch") }
        } else emptyList()
        val tags = if (branchesOnly || qualifiedBranch) emptyList() else {
            gql.execute(GitUiTags, GitUiTags.Variables(id, offset, 26, name)).git.tags
                .map { mapOf("name" to it.name, "kind" to "tag") }
        }
        return branches + tags
    }
}
