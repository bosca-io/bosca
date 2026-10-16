package bosca.git.ui

import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.GitUiRefs
import bosca.git.ui.graphql.GitUiCommit
import bosca.git.ui.graphql.GitUiTag

/**
 * Resolves named refs before calling the SHA-only commit API; missing and unborn refs return null.
 * [knownRefs] uses fully qualified names to preserve branch and tag namespaces.
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
                tags.associate { "refs/tags/${it.name}" to (it.targetSha ?: it.sha) } +
                    branches.associate { "refs/heads/${it.name}" to it.sha }
            }
    suspend fun tagSha(name: String): String? =
        execute(GitUiTag, GitUiTag.Variables(repositoryId, name)).git.tag?.let { it.targetSha ?: it.sha }
    val sha = when {
        ref.startsWith("refs/heads/") -> refs[ref]
        ref.startsWith("refs/tags/") -> refs[ref] ?: tagSha(ref.removePrefix("refs/tags/"))
        ref.startsWith("refs/") -> null
        else -> refs["refs/heads/$ref"] ?: refs["refs/tags/$ref"] ?: tagSha(ref)
            ?: ref.takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
    } ?: return null
    return execute(GitUiCommit, GitUiCommit.Variables(repositoryId, sha)).git.commit?.sha
}
