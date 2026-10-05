package bosca.content.timeevent.repository

import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventType
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

@Repository
interface TimeEventRepository {

    // Time Event Types

    @Query("select * from time_event_types order by name")
    suspend fun getTypes(): List<TimeEventType>

    @Query("select * from time_event_types where id = :id")
    suspend fun getType(id: String): TimeEventType?

    @Query("insert into time_event_types (id, name, description, schema, configuration) values (:id, :name, :description, :schema, :configuration) returning *")
    suspend fun addType(id: String, name: String, description: String, schema: JsonElement?, configuration: JsonElement?): TimeEventType

    @Query("update time_event_types set name = :name, description = :description, schema = :schema, configuration = :configuration where id = :id returning *")
    suspend fun updateType(id: String, name: String, description: String, schema: JsonElement?, configuration: JsonElement?): TimeEventType?

    @Query("delete from time_event_types where id = :id")
    suspend fun deleteType(id: String)

    // Time Events

    @Query("select * from time_events where metadata_id = :metadataId and metadata_version = :metadataVersion order by start_offset_ms, sort")
    suspend fun getTimeEvents(metadataId: UUID, metadataVersion: Int): List<TimeEvent>

    @Query("select * from time_events where metadata_id = :metadataId and metadata_version = :metadataVersion and type = :type order by start_offset_ms, sort")
    suspend fun getTimeEventsByType(metadataId: UUID, metadataVersion: Int, type: String): List<TimeEvent>

    @Query("""
        select * from time_events
        where metadata_id = :metadataId and metadata_version = :metadataVersion
        and start_offset_ms <= :offsetMs
        and (end_offset_ms is null or end_offset_ms > :offsetMs)
        order by sort
    """)
    suspend fun getTimeEventsAtOffset(metadataId: UUID, metadataVersion: Int, offsetMs: Long): List<TimeEvent>

    @Query("select * from time_events where id = :id")
    suspend fun getTimeEvent(id: UUID): TimeEvent?

    @Query("""
        insert into time_events (metadata_id, metadata_version, type, start_offset_ms, end_offset_ms, sort, attributes)
        values (:metadataId, :metadataVersion, :type, :startOffsetMs, :endOffsetMs, :sort, :attributes)
        returning *
    """)
    suspend fun addTimeEvent(
        metadataId: UUID,
        metadataVersion: Int,
        type: String,
        startOffsetMs: Long,
        endOffsetMs: Long?,
        sort: Int,
        attributes: JsonElement
    ): TimeEvent

    @Query("""
        update time_events
        set type = :type, start_offset_ms = :startOffsetMs, end_offset_ms = :endOffsetMs,
            sort = :sort, attributes = :attributes, modified = now()
        where id = :id
        returning *
    """)
    suspend fun updateTimeEvent(
        id: UUID,
        type: String,
        startOffsetMs: Long,
        endOffsetMs: Long?,
        sort: Int,
        attributes: JsonElement
    ): TimeEvent?

    @Query("delete from time_events where id = :id")
    suspend fun deleteTimeEvent(id: UUID)

    @Query("delete from time_events where metadata_id = :metadataId and metadata_version = :metadataVersion")
    suspend fun deleteAllTimeEvents(metadataId: UUID, metadataVersion: Int)

    @Query("delete from time_events where metadata_id = :metadataId and metadata_version = :metadataVersion and type = :type")
    suspend fun deleteTimeEventsByType(metadataId: UUID, metadataVersion: Int, type: String)

    @Query("select * from time_events where id = any(:ids)")
    suspend fun getTimeEventsByIds(ids: List<UUID>): List<TimeEvent>

    @Query("delete from time_events where id = any(:ids)")
    suspend fun deleteTimeEventsByIds(ids: List<UUID>)
}
