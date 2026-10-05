package bosca.content.timeevent.repository

import bosca.content.timeevent.model.TimeEventTypeAttribute
import bosca.db.annotation.Query
import bosca.db.annotation.Repository

/**
 * Database access layer for time event type attribute definitions. Provides
 * CRUD operations for managing the attribute schema associated with each
 * time event type.
 */
@Repository
interface TimeEventTypeAttributeRepository {

    @Query("insert into time_event_type_attributes (type_id, key, name, description, supplementary_key, configuration, type, ui, list, sort, tools) values (:typeId, :key, :name, :description, :supplementaryKey, :configuration, :type, :ui, :list, :sort, :tools) returning *")
    suspend fun add(attribute: TimeEventTypeAttribute): TimeEventTypeAttribute

    @Query("select * from time_event_type_attributes where type_id = :typeId order by sort")
    suspend fun getByTypeId(typeId: String): List<TimeEventTypeAttribute>

    @Query("delete from time_event_type_attributes where type_id = :typeId")
    suspend fun deleteByTypeId(typeId: String)

    @Query("delete from time_event_type_attributes where type_id = :typeId and key = :key")
    suspend fun deleteByTypeIdAndKey(typeId: String, key: String)
}
