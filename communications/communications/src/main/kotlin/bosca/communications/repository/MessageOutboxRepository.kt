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

    @Query("select * from communications.message_outbox where id = :id and sent_at is null")
    suspend fun getPending(id: UUID): MessageOutboxEntry?

    @Query(
        """
        select * from communications.message_outbox
        where sent_at is null
        order by created_at, position
        limit :limit
        """
    )
    suspend fun getPending(limit: Int): List<MessageOutboxEntry>

    @Query(
        """
        update communications.message_outbox
        set sent_at = now(), attempts = attempts + 1, last_error = null
        where id = :id and sent_at is null
        """,
        returnUpdateCount = true,
    )
    suspend fun markSent(id: UUID): Int

    @Query(
        """
        update communications.message_outbox
        set attempts = attempts + 1, last_error = :error
        where id = :id and sent_at is null
        """,
        returnUpdateCount = true,
    )
    suspend fun markFailure(id: UUID, error: String): Int
}
