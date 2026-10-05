package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.serialization.UUID

@Repository
interface DeliveryEventRepository {

    @Query("""
        insert into communications.delivery_events (
            provider_event_id, message_id, recipient_id, channel, status,
            provider_event, error_code, error_message, metadata, created_at
        )
        values (
            :providerEventId, :messageId, :recipientId, :channel::communications.channel,
            :status::communications.delivery_status_type, :providerEvent, :errorCode,
            :errorMessage, :metadata::jsonb, :createdAt
        )
        on conflict (provider_event_id) where provider_event_id is not null do nothing
        returning $DELIVERY_EVENT_COLUMNS
    """)
    suspend fun insert(
        providerEventId: String?,
        messageId: UUID,
        recipientId: UUID,
        channel: DeliveryChannel,
        status: DeliveryStatusType,
        providerEvent: String?,
        errorCode: String?,
        errorMessage: String?,
        metadata: String?,
        createdAt: bosca.serialization.OffsetDateTime,
    ): DeliveryEvent?

    @Query("""
        select $DELIVERY_EVENT_COLUMNS
        from communications.delivery_events
        where message_id = :messageId
        order by created_at desc
    """)
    suspend fun getByMessageId(messageId: UUID): List<DeliveryEvent>

    @Query("""
        select $DELIVERY_EVENT_COLUMNS
        from communications.delivery_events
        where recipient_id = :recipientId
        order by created_at desc
        limit :limit offset :offset
    """)
    suspend fun getByRecipientId(recipientId: UUID, offset: Long, limit: Int): List<DeliveryEvent>
}

private const val DELIVERY_EVENT_COLUMNS = """
    id,
    provider_event_id,
    message_id,
    recipient_id,
    channel,
    status,
    provider_event,
    error_code,
    error_message,
    metadata,
    created_at
"""
