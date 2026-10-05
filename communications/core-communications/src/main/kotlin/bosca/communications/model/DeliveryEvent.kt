package bosca.communications.model

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * An individual delivery event — either from a SendGrid webhook
 * callback or from internal state transitions (enqueued, sent).
 * Events are append-only; the [DeliveryStatus] aggregate is
 * derived from them per message, recipient, and channel.
 */
@Serializable
data class DeliveryEvent(
    val id: UUID? = null,
    /**
     * Stable event identifier used to make provider callbacks and internal
     * bookkeeping retries idempotent.
     */
    @ColumnName("provider_event_id")
    val providerEventId: String? = null,
    @ColumnName("message_id")
    val messageId: UUID,
    @ColumnName("recipient_id")
    val recipientId: UUID,
    val channel: DeliveryChannel = DeliveryChannel.EMAIL,
    val status: DeliveryStatusType,
    @ColumnName("provider_event")
    val providerEvent: String? = null,
    @ColumnName("error_code")
    val errorCode: String? = null,
    @ColumnName("error_message")
    val errorMessage: String? = null,
    val metadata: JsonElement? = null,
    @ColumnName("created_at")
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
