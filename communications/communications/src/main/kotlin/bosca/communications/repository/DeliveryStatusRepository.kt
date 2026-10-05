package bosca.communications.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.communications.model.DeliveryChannel
import bosca.communications.model.DeliveryStatus
import bosca.serialization.UUID

@Repository
interface DeliveryStatusRepository {

    @Query("""
        select $DELIVERY_STATUS_COLUMNS
        from communications.delivery_status
        where message_id = :messageId
          and recipient_id = :recipientId
          and channel = :channel::communications.channel
    """)
    suspend fun get(
        messageId: UUID,
        recipientId: UUID,
        channel: DeliveryChannel = DeliveryChannel.EMAIL,
    ): DeliveryStatus?

    @Query("""
        select $DELIVERY_STATUS_COLUMNS
        from communications.delivery_status
        where message_id = :messageId
          and recipient_id = :recipientId
          and channel = :channel::communications.channel
        for update
    """)
    suspend fun getForUpdate(
        messageId: UUID,
        recipientId: UUID,
        channel: DeliveryChannel,
    ): DeliveryStatus?

    @Query("""
        select $DELIVERY_STATUS_COLUMNS
        from communications.delivery_status
        where message_id = :messageId
        order by updated_at desc
    """)
    suspend fun getByMessageId(messageId: UUID): List<DeliveryStatus>

    @Query("""
        select $DELIVERY_STATUS_COLUMNS
        from communications.delivery_status
        where recipient_id = :recipientId
        order by updated_at desc
        limit :limit offset :offset
    """)
    suspend fun getByRecipientId(recipientId: UUID, offset: Long, limit: Int): List<DeliveryStatus>

    @Query("""
        select $DELIVERY_STATUS_COLUMNS
        from communications.delivery_status
        order by updated_at desc, message_id desc, recipient_id desc, channel desc
        limit :limit offset :offset
    """)
    suspend fun getAll(offset: Long, limit: Int): List<DeliveryStatus>

    @Query("select count(*) from communications.delivery_status")
    suspend fun count(): Long

    @Query("""
        insert into communications.delivery_status (
            message_id, recipient_id, channel, status, attempts, last_attempt_at,
            delivered_at, bounced_at, opened_at, clicked_at,
            error_code, error_message, bml_template, created_at, updated_at
        )
        values (
            :messageId,
            :recipientId,
            :channel::communications.channel,
            :status::communications.delivery_status_type,
            :attempts,
            :lastAttemptAt,
            :deliveredAt,
            :bouncedAt,
            :openedAt,
            :clickedAt,
            :errorCode,
            :errorMessage,
            :bmlTemplate::jsonb,
            :createdAt,
            :updatedAt
        )
        on conflict (message_id, recipient_id, channel)
        do nothing
    """, returnUpdateCount = true)
    suspend fun insert(status: DeliveryStatus): Int

    @Query("""
        update communications.delivery_status
        set status = :status::communications.delivery_status_type,
            attempts = :attempts,
            last_attempt_at = :lastAttemptAt,
            delivered_at = :deliveredAt,
            bounced_at = :bouncedAt,
            opened_at = :openedAt,
            clicked_at = :clickedAt,
            error_code = :errorCode,
            error_message = :errorMessage,
            bml_template = :bmlTemplate::jsonb,
            created_at = :createdAt,
            updated_at = :updatedAt
        where message_id = :messageId
          and recipient_id = :recipientId
          and channel = :channel::communications.channel
    """)
    suspend fun update(status: DeliveryStatus)
}

private const val DELIVERY_STATUS_COLUMNS = """
    message_id,
    recipient_id,
    channel,
    status,
    attempts,
    last_attempt_at,
    delivered_at,
    bounced_at,
    opened_at,
    clicked_at,
    error_code,
    error_message,
    bml_template,
    created_at,
    updated_at
"""
