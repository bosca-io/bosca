package bosca.git.ui

import bosca.git.ui.graphql.GitUiPullData
import kotlinx.serialization.Serializable

/** Chronological discussion sharing one review, file, commit and line anchor. */
@Serializable
data class PullReviewThread(val comments: List<GitUiPullData.Git.PullRequest.Reviews.Comments>) {
    val first: GitUiPullData.Git.PullRequest.Reviews.Comments
        get() = comments.first()

    val id: String
        get() = first.id

    val line: Int
        get() = first.newLineNumber ?: first.oldLineNumber ?: 0

    val hasLine: Boolean
        get() = line > 0

    val resolved: Boolean
        get() = comments.all { it.resolved }

    val outdated: Boolean
        get() = first.outdated

    val replyAnchor: String
        get() = "reply:$id"

    val anchor: String
        get() = "discussion-$id"

    val location: String
        get() = first.filePath + if (line > 0) ":$line" else ""

    /** Matches the side of the line on which this discussion was started. */
    fun matches(file: String, old: Int?, new: Int?): Boolean =
        !outdated && hasLine && first.filePath == file &&
            (if (first.newLineNumber != null) first.newLineNumber == new
            else first.oldLineNumber == old)
}
