package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Events that can trigger webhook delivery. A webhook subscribes to a subset
 * of these; only matching events are delivered.
 */
@DbMapper(WebhookEventMapper::class)
@Serializable
enum class WebhookEvent {
    PUSH,
    PULL_REQUEST_OPENED,
    PULL_REQUEST_CLOSED,
    PULL_REQUEST_MERGED,
    PULL_REQUEST_UPDATED,
    REVIEW_SUBMITTED,
    BRANCH_CREATED,
    BRANCH_DELETED,
    TAG_CREATED,
    TAG_DELETED,
    PIPELINE_RUN_STARTED,
    PIPELINE_RUN_COMPLETED
}

object WebhookEventMapper : EnumMapper<WebhookEvent>({ WebhookEvent.valueOf(it.uppercase()) })

/**
 * A webhook endpoint that receives HTTP POST notifications when subscribed
 * events occur on a repository. Payloads are signed with HMAC SHA-256 using
 * the [secret] and follow GitHub webhook payload format for ecosystem compatibility.
 */
@Serializable
data class Webhook(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("repository_id") val repositoryId: UUID,
    val url: String,
    val secret: String,
    val events: List<WebhookEvent> = emptyList(),
    val active: Boolean = true,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)

/**
 * A single delivery attempt for a webhook. Tracks the HTTP response status,
 * retry count, and timing for debugging failed deliveries.
 */
@Serializable
data class WebhookDelivery(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("webhook_id") val webhookId: UUID,
    val event: WebhookEvent,
    val payload: String,
    @ColumnName("response_status") val responseStatus: Int? = null,
    @ColumnName("response_body") val responseBody: String? = null,
    @Contextual @ColumnName("delivered_at") val deliveredAt: OffsetDateTime? = null,
    @ColumnName("retry_count") val retryCount: Int = 0,
    val success: Boolean = false,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now()
)
