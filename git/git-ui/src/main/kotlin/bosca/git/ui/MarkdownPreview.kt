package bosca.git.ui

import bml.generated.GitMarkdownPreview
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute
import bosca.git.ui.graphql.*

class MarkdownPreview : GitMarkdownPreview {
    /** Renders a draft using the selected repository's permission-checked metadata and commit. */
    override suspend fun render(
        gql: GraphQLClient,
        id: String,
        ref: String,
        path: String,
        source: String,
    ): String {
        val repo =
            gql.execute(GitUiRepository, GitUiRepository.Variables(id)).git.repositoryById
                ?: error("Repository not found")
        val sha =
            gql.commitShaForRef(id, ref)
                ?: run {
                    require(
                        ref == repo.defaultBranch &&
                            gql.execute(GitUiBranches, GitUiBranches.Variables(id))
                                .git
                                .branches
                                .isEmpty()
                    ) {
                        "Ref not found"
                    }
                    ref
                }
        return repositoryMarkdown(
            source,
            id,
            sha,
            repositoryFilePath(path),
            repo.cloneUrl,
            "/repositories",
        )
    }
}
