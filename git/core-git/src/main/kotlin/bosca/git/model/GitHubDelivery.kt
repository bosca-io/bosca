package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.events.Event
import bosca.events.annotation.JobEvent
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Verified intake, retained independently of pipeline execution for retry and reconciliation. */
@Serializable
@JobEvent(jobs = [])
data class GitHubDelivery(
    @ColumnName("delivery_id") val deliveryId: String,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val event: String,
    val payload: JsonElement,
    @ColumnName("payload_digest") val payloadDigest: String,
    @ColumnName("github_user_id") val githubUserId: Long? = null,
    @Contextual @ColumnName("principal_id") val principalId: UUID? = null,
    val ignored: Boolean = false,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
) : Event {
    override fun identityKey(): Any = deliveryId
}

/** Intake-only fields are decoded from the signed JSON, never from request query parameters. */
@Serializable
data class GitHubWebhookPayload(
    val repository: GitHubWebhookRepository,
    val sender: GitHubWebhookUser? = null,
    @kotlinx.serialization.SerialName("pull_request") val pullRequest: GitHubWebhookPullRequest? = null,
)

@Serializable
data class GitHubWebhookRepository(val id: Long)

@Serializable
data class GitHubWebhookUser(val id: Long, val type: String = "")

@Serializable
data class GitHubWebhookPullRequest(val head: GitHubWebhookPullRequestHead)

@Serializable
data class GitHubWebhookPullRequestHead(val repo: GitHubWebhookRepository? = null)

/** The request was authentic but reused a delivery identifier with different content or routing. */
class GitHubDeliveryConflictException : IllegalArgumentException("GitHub delivery identifier was reused")

/** Authenticity or paired repository validation failed; no delivery is persisted. */
class GitHubWebhookRejectedException : IllegalArgumentException("GitHub webhook rejected")

/** The delivery has malformed headers or encoding, independently of integration configuration. */
class GitHubWebhookInputException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** A required server-side credential is unavailable; intake must not acknowledge the delivery. */
class GitHubWebhookUnavailableException : RuntimeException("GitHub webhook configuration is unavailable")
