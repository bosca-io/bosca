package bosca.git.model

import bosca.serialization.OffsetDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

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
    @SerialName("merged_by") val mergedBy: GitHubPullRequestUser? = null,
    val head: GitHubPullRequestBranch,
    val base: GitHubPullRequestBranch,
)

@Serializable
data class GitHubPullRequestUser(val id: Long, val login: String, val type: String)

@Serializable
data class GitHubPullRequestBranch(val ref: String, val sha: String, val repo: GitHubWebhookRepository? = null)

/** The signed PR state binds the originating user's authority to that exact provider observation. */
@Serializable
data class GitHubPullRequestDelivery(val number: Int, @SerialName("pull_request") val pullRequest: GitHubPullRequest? = null)

/** GitHub creates a counterpart under the token's identity; original authorship belongs in its body. */
@Serializable
data class GitHubCreatePullRequestInput(
    val title: String,
    val body: String?,
    val head: String,
    val base: String,
    val draft: Boolean,
)

/**
 * Changes selected PR fields through REST. Null omits a field; [kotlinx.serialization.json.JsonNull]
 * explicitly clears [body]. GitHub does not support conditional PR updates, so callers send only changes.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GitHubUpdatePullRequestInput(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val title: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val body: JsonPrimitive? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val base: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val state: String? = null,
)
