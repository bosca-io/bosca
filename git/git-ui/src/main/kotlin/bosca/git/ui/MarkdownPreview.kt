package bosca.git.ui

import bml.generated.GitMarkdownPreview
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.*
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

class MarkdownPreview : GitMarkdownPreview {
    /** Renders a draft using the selected repository's permission-checked metadata and commit. */
    override suspend fun render(
        gql: GraphQLClient,
        id: String,
        ref: String,
        path: String,
        source: String,
    ): String = supervisorScope {
        val refsRequest = async { gql.execute(GitUiRefs, GitUiRefs.Variables(id)).git }
        val repo =
            gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
                ?: run {
                    refsRequest.cancel()
                    error("Repository not found")
                }
        val refs = refsRequest.await()
        val known = refs.tags.associate { it.name to (it.targetSha ?: it.sha) } +
            refs.branches.associate { it.name to it.sha }
        val sha =
            gql.commitShaForRef(id, ref, known)
                ?: run {
                    require(
                        ref == repo.defaultBranch && refs.branches.isEmpty()
                    ) {
                        "Ref not found"
                    }
                    ref
                }
        repositoryMarkdown(
            source,
            id,
            sha,
            repositoryFilePath(path),
            repo.cloneUrl,
            "/repositories",
        )
    }
}
