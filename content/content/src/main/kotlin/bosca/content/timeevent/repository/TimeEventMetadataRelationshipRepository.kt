package bosca.content.timeevent.repository

import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

/**
 * Repository for managing relationships between time events and metadata items.
 * Each relationship is uniquely identified by the combination of time event ID,
 * metadata ID, and relationship type, allowing a time event to link to multiple
 * metadata items under different relationship semantics.
 */
@Repository
interface TimeEventMetadataRelationshipRepository {

    /**
     * Creates a new relationship between a time event and a metadata item.
     *
     * @param relationship the relationship to create
     * @return the created relationship
     */
    @Query("""
        insert into time_event_metadata_relationships (time_event_id, metadata_id, metadata_version, relationship, attributes)
        values (:timeEventId, :metadataId, :metadataVersion, :relationship, :attributes)
        returning *
    """)
    suspend fun add(relationship: TimeEventMetadataRelationship): TimeEventMetadataRelationship

    /**
     * Retrieves all metadata relationships for a given time event, ordered by relationship type.
     *
     * @param timeEventId the time event identifier
     * @return the list of metadata relationships
     */
    @Query("select * from time_event_metadata_relationships where time_event_id = :timeEventId order by relationship, metadata_id")
    suspend fun getByTimeEventId(timeEventId: UUID): List<TimeEventMetadataRelationship>

    /**
     * Retrieves metadata relationships for a given time event filtered by relationship type.
     *
     * @param timeEventId the time event identifier
     * @param relationship the relationship type to filter by
     * @return the list of matching metadata relationships
     */
    @Query("select * from time_event_metadata_relationships where time_event_id = :timeEventId and relationship = :relationship order by metadata_id")
    suspend fun getByTimeEventIdAndRelationship(timeEventId: UUID, relationship: String): List<TimeEventMetadataRelationship>

    /**
     * Retrieves all metadata relationships for a batch of time events.
     *
     * @param timeEventIds the list of time event identifiers
     * @return the list of metadata relationships across all specified time events
     */
    @Query("select * from time_event_metadata_relationships where time_event_id = any(:timeEventIds) order by time_event_id, relationship, metadata_id")
    suspend fun getByTimeEventIds(timeEventIds: List<UUID>): List<TimeEventMetadataRelationship>

    /**
     * Deletes a specific relationship between a time event and a metadata item.
     *
     * @param timeEventId the time event identifier
     * @param metadataId the metadata item identifier
     * @param relationship the relationship type
     */
    @Query("delete from time_event_metadata_relationships where time_event_id = :timeEventId and metadata_id = :metadataId and relationship = :relationship")
    suspend fun delete(timeEventId: UUID, metadataId: UUID, relationship: String)

    /**
     * Deletes all metadata relationships for a given time event.
     *
     * @param timeEventId the time event identifier
     */
    @Query("delete from time_event_metadata_relationships where time_event_id = :timeEventId")
    suspend fun deleteByTimeEventId(timeEventId: UUID)

    /**
     * Retrieves the attributes for a specific relationship identified by its composite key.
     *
     * @param timeEventId the time event identifier
     * @param metadataId the metadata item identifier
     * @param relationship the relationship type
     * @return the attributes JSON, or null if the relationship does not exist or has no attributes
     */
    @Query("select attributes from time_event_metadata_relationships where time_event_id = :timeEventId and metadata_id = :metadataId and relationship = :relationship")
    suspend fun getAttributes(timeEventId: UUID, metadataId: UUID, relationship: String): JsonElement?

    /**
     * Updates the attributes on an existing relationship.
     *
     * @param timeEventId the time event identifier
     * @param metadataId the metadata item identifier
     * @param relationship the relationship type
     * @param attributes the new attributes to set
     */
    @Query("update time_event_metadata_relationships set attributes = :attributes where time_event_id = :timeEventId and metadata_id = :metadataId and relationship = :relationship")
    suspend fun setAttributes(timeEventId: UUID, metadataId: UUID, relationship: String, attributes: JsonElement)
}
