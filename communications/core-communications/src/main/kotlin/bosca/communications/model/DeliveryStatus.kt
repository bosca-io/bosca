package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Aggregate delivery status for a message to a specific recipient
 * on a specific channel. Milestone timestamps retain every observed event,
 * while the primary status advances monotonically through the delivery lifecycle.
 */
@Serializable
@BatchKey("recipientId")
data class DeliveryStatus(
    @ColumnName("message_id")
    val messageId: UUID,
    @ColumnName("recipient_id")
    val recipientId: UUID,
    val channel: DeliveryChannel = DeliveryChannel.EMAIL,
    val status: DeliveryStatusType = DeliveryStatusType.PENDING,
    val attempts: Int = 0,
    @ColumnName("last_attempt_at")
    val lastAttemptAt: OffsetDateTime? = null,
    @ColumnName("delivered_at")
    val deliveredAt: OffsetDateTime? = null,
    @ColumnName("bounced_at")
    val bouncedAt: OffsetDateTime? = null,
    @ColumnName("opened_at")
    val openedAt: OffsetDateTime? = null,
    @ColumnName("clicked_at")
    val clickedAt: OffsetDateTime? = null,
    @ColumnName("error_code")
    val errorCode: String? = null,
    @ColumnName("error_message")
    val errorMessage: String? = null,
    @ColumnName("bml_template")
    @property:DbMapper(JsonbMapper::class)
    val bmlTemplate: BmlMessageTemplateRender? = null,
    @ColumnName("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("updated_at")
    val updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * Delivery lifecycle states. Mirrors SendGrid event types with
 * additional internal states for queue management.
 */
@Serializable
enum class DeliveryStatusType {
    PENDING,
    SENT,
    DELIVERED,
    DEFERRED,
    BOUNCED,
    DROPPED,
    OPENED,
    CLICKED,
    SPAM_REPORT,
    UNSUBSCRIBED,
    FAILED,
}
