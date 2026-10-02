package bosca.git.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GitHub's current pull request, including the original author and unpeeled branch tips. */
@Serializable
data class GitHubPullRequest(
    val id: Long,
    val number: Int,
    @SerialName("node_id") val nodeId: String,
    val title: String,
    val body: String? = null,
    val state: String,
    val draft: Boolean = false,
    val merged: Boolean = false,
    @SerialName("merge_commit_sha") val mergeSha: String? = null,
    @Contextual @SerialName("merged_at") val mergedAt: OffsetDateTime? = null,
    @Contextual @SerialName("updated_at") val modified: OffsetDateTime,
    val user: GitHubPullRequestUser,
    val head: GitHubPullRequestBranch,
    val base: GitHubPullRequestBranch,
)

@Serializable
data class GitHubPullRequestUser(val id: Long, val login: String, val type: String)

@Serializable
data class GitHubPullRequestBranch(val ref: String, val sha: String, val repo: GitHubWebhookRepository? = null)

/** GitHub creates a counterpart under the token's identity; original authorship belongs in its body. */
@Serializable
data class GitHubCreatePullRequestInput(
    val title: String,
    val body: String?,
    val head: String,
    val base: String,
    val draft: Boolean,
)

/** GitHub permits title, body, base branch, and open/closed lifecycle updates through REST. */
@Serializable
data class GitHubUpdatePullRequestInput(
    val title: String,
    val body: String?,
    val base: String,
    val state: String,
)
