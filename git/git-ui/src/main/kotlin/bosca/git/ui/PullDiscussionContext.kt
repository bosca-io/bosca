package bosca.git.ui

import bosca.bml.graphql.GraphQLClient
import bosca.git.ui.graphql.GitPullRequestStatus

/**
 * Reveals unchanged lines that carry current discussions so they stay beside their code. A file
 * whose source cannot be shown keeps those discussions in the list outside the diff.
 */
internal suspend fun PullPage.withDiscussionContext(gql: GraphQLClient): PullPage {
    if (selectedCommit != null) return this
    val lines = pull.reviews
        .flatMap { it.comments }
        .filter { !it.outdated && (pull.status != GitPullRequestStatus.MERGED || it.commitSha == headSha) }
        .mapNotNull { comment -> comment.newLineNumber?.let { comment.filePath to it } }
        .groupBy({ it.first }, { it.second })
    val targets = diff.filter { file -> lines[file.path]?.any(file::hides) == true }
    if (targets.isEmpty()) return this
    val revealed = targets
        .loadConcurrently { file ->
            gql.diffSourceLinesOrNull(sourceRepository, headSha, file)?.let { file.path to file.reveal(lines.getValue(file.path), it) }
        }
        .filterNotNull()
        .toMap()
    return copy(diff = diff.map { revealed[it.path] ?: it })
}
