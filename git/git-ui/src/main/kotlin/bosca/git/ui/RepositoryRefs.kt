package bosca.git.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.GitUiRefs
import bosca.git.ui.graphql.GitUiCommit

/**
 * Resolves named refs before calling the SHA-only commit API; missing and unborn refs return null.
 */
internal suspend fun GraphQLClient.commitShaForRef(
    repositoryId: String,
    ref: String,
    knownRefs: Map<String, String>? = null,
): String? {
    val refs =
        knownRefs
            ?: run {
                val data = execute(GitUiRefs, GitUiRefs.Variables(repositoryId)).git
                val branches = data.branches
                val tags = data.tags
                tags.associate { it.name to (it.targetSha ?: it.sha) } +
                    branches.associate { it.name to it.sha }
            }
    val sha = refs[ref] ?: ref.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) } ?: return null
    return execute(GitUiCommit, GitUiCommit.Variables(repositoryId, sha)).git.commit?.sha
}
