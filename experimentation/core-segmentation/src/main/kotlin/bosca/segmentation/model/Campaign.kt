package bosca.segmentation.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A campaign targeted at the audience of one or more segments, delivered
 * through a specified channel (banner, email, or push).
 *
 * Campaigns move through a lifecycle ([NotificationStatus]) from
 * draft to sent. The [content] field holds channel-specific payload as JSON
 * (e.g., a BML message template reference and payload, banner styling, or push title/body).
 */
@BatchKey("id")
@Serializable
data class Campaign(
    @Contextual
    val id: UUID = UUID.NIL,
    val name: String,
    val channel: NotificationChannel,
    val status: NotificationStatus = NotificationStatus.DRAFT,
    @Contextual
    val content: JsonElement? = null,
    @ColumnName("scheduled_job_id")
    @Contextual
    val scheduledJobId: UUID? = null,
    @ColumnName("scheduled_at")
    @Contextual
    val scheduledAt: OffsetDateTime? = null,
    @ColumnName("ended_at")
    @Contextual
    val endedAt: OffsetDateTime? = null,
    val placement: String? = null,
    val weight: Int = 0,
    @ColumnName("sent_at")
    @Contextual
    val sentAt: OffsetDateTime? = null,
    @ColumnName("sent_count")
    val sentCount: Long = 0,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now()
)
