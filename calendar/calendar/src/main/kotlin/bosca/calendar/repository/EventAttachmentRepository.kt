package bosca.calendar.repository

import bosca.calendar.model.EventAttachment
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface EventAttachmentRepository {

    @Query("select * from calendar.event_attachments where event_id = :eventId order by created asc")
    suspend fun getByEventId(eventId: UUID): List<EventAttachment>

    @Query("""
        insert into calendar.event_attachments (event_id, metadata_id, collection_id, relationship)
        values (:eventId, :metadataId, :collectionId, :relationship)
        returning *
    """)
    suspend fun add(eventId: UUID, metadataId: UUID?, collectionId: UUID?, relationship: String): EventAttachment

    @Query("delete from calendar.event_attachments where id = :id")
    suspend fun deleteById(id: UUID)

    @Query("select * from calendar.event_attachments where id = :id")
    suspend fun getById(id: UUID): EventAttachment?
}
