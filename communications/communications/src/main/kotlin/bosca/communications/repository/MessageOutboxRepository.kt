package bosca.communications.repository

import bosca.communications.model.MessageOutboxEntry
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface MessageOutboxRepository {

    /**
     * Claims [sourceId] and inserts its complete message snapshot in one statement. A concurrent or
     * repeated caller receives an empty list and must load the snapshot already owned by the source.
     */
    @Query(
        """
        select *
        from communications.create_message_outbox(:sourceId, cast(:messages as jsonb))
        """
    )
    suspend fun create(sourceId: UUID, messages: String): List<MessageOutboxEntry>

    @Query(
        """
        select * from communications.message_outbox
        where source_id = :sourceId
        order by position
        """
    )
    suspend fun getBySource(sourceId: UUID): List<MessageOutboxEntry>

    /** Retrieves an unsent delivery while its persisted attempt budget remains. */
    @Query("select * from communications.message_outbox where id = :id and sent_at is null and attempts < ${MessageOutboxEntry.MAX_ATTEMPTS}")
    suspend fun getPending(id: UUID): MessageOutboxEntry?

    /** Lists recoverable deliveries, excluding sent rows and exhausted attempt budgets. */
    @Query(
        """
        select * from communications.message_outbox
        where sent_at is null and attempts < ${MessageOutboxEntry.MAX_ATTEMPTS}
        order by created_at, position
        limit :limit
        """
    )
    suspend fun getPending(limit: Int): List<MessageOutboxEntry>

    /** Records provider acceptance once while the delivery remains pending. */
    @Query(
        """
        update communications.message_outbox
        set sent_at = now(), attempts = attempts + 1, last_error = null
        where id = :id and sent_at is null and attempts < ${MessageOutboxEntry.MAX_ATTEMPTS}
        """,
        returnUpdateCount = true,
    )
    suspend fun markSent(id: UUID): Int

    /** Records an unsuccessful attempt; reaching [MessageOutboxEntry.MAX_ATTEMPTS] closes the delivery. */
    @Query(
        """
        update communications.message_outbox
        set attempts = attempts + 1, last_error = :error
        where id = :id and sent_at is null and attempts < ${MessageOutboxEntry.MAX_ATTEMPTS}
        """,
        returnUpdateCount = true,
    )
    suspend fun markFailure(id: UUID, error: String): Int
}
