package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub-compatible webhook payload structures. Each event type has its own
 * concrete payload class containing the repository context, sender, and
 * event-specific data. Serialized to JSON for HTTP delivery.
 */
@Serializable
sealed class WebhookPayload {
    abstract val action: String
    abstract val repository: WebhookRepositoryInfo
    abstract val sender: WebhookSenderInfo?
}

@Serializable
@SerialName("push")
data class PushPayload(
    override val action: String = "push",
    val ref: String,
    val before: String,
    val after: String,
    val commits: List<WebhookCommitInfo> = emptyList(),
    override val repository: WebhookRepositoryInfo,
    override val sender: WebhookSenderInfo? = null
) : WebhookPayload()

@Serializable
@SerialName("pull_request")
data class PullRequestPayload(
    override val action: String,
    @SerialName("pull_request") val pullRequest: WebhookPullRequestInfo,
    override val repository: WebhookRepositoryInfo,
    override val sender: WebhookSenderInfo? = null
) : WebhookPayload()

@Serializable
@SerialName("branch")
data class BranchPayload(
    override val action: String,
    @SerialName("ref") val refName: String,
    val sha: String,
    override val repository: WebhookRepositoryInfo,
    override val sender: WebhookSenderInfo? = null
) : WebhookPayload()

@Serializable
@SerialName("tag")
data class TagPayload(
    override val action: String,
    @SerialName("ref") val refName: String,
    val sha: String,
    override val repository: WebhookRepositoryInfo,
    override val sender: WebhookSenderInfo? = null
) : WebhookPayload()

@Serializable
data class WebhookRepositoryInfo(
    @Contextual val id: UUID,
    val name: String,
    val slug: String,
    val visibility: String,
    @SerialName("default_branch") val defaultBranch: String
)

@Serializable
data class WebhookSenderInfo(
    @Contextual val id: UUID,
    val name: String? = null
)

@Serializable
data class WebhookCommitInfo(
    val sha: String,
    val message: String,
    @SerialName("author_name") val authorName: String,
    @SerialName("author_email") val authorEmail: String,
    val timestamp: String
)

@Serializable
data class WebhookPullRequestInfo(
    @Contextual val id: UUID,
    val number: Int,
    val title: String,
    val description: String? = null,
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("target_branch") val targetBranch: String,
    val status: String,
    @SerialName("merge_sha") val mergeSha: String? = null
)
