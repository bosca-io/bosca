package bosca.content.timeevent.service

import bosca.attributes.TemplateAttributeInput
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.model.TimeEventTypeInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Service for managing time events and their type definitions on media content
 * timelines. Provides operations for creating, updating, and querying both
 * time event instances (markers on a specific media item's timeline) and
 * time event types (the classification schema that defines what kinds of
 * markers are available).
 */
interface TimeEventService : Service {

    // --- Time Event Types ---

    /**
     * Retrieves all registered time event type definitions, ordered by name.
     *
     * @return the complete list of time event types
     */
    suspend fun getTypes(): List<TimeEventType>

    /**
     * Looks up a time event type by its string identifier.
     *
     * @param id the type identifier (e.g. "chapter", "caption")
     * @return the time event type, or null if not found
     */
    suspend fun getType(id: String): TimeEventType?

    /**
     * Creates a new time event type definition.
     *
     * @param type the type definition to create
     * @return the created time event type
     */
    suspend fun addType(type: TimeEventTypeInput): TimeEventType

    /**
     * Updates an existing time event type definition.
     *
     * @param id the type identifier to update
     * @param type the updated type definition
     * @return the updated time event type, or null if not found
     */
    suspend fun editType(id: String, type: TimeEventTypeInput): TimeEventType?

    /**
     * Deletes a time event type definition and all of its associated attribute
     * definitions. Will fail with a foreign key violation if time events of this
     * type still exist — delete or reassign them first.
     *
     * @param id the type identifier to delete
     */
    suspend fun deleteType(id: String)

    // --- Time Event Type Attributes ---

    /**
     * Retrieves the attribute definitions for a time event type, ordered by sort position.
     *
     * @param typeId the type identifier
     * @return the list of template attribute definitions
     */
    suspend fun getTypeAttributes(typeId: String): List<TemplateAttribute>

    /**
     * Adds a new attribute definition to a time event type at the specified sort position.
     *
     * @param typeId the type identifier
     * @param attribute the attribute definition to add
     * @param sort the sort position for the new attribute
     */
    suspend fun addTypeAttribute(typeId: String, attribute: TemplateAttributeInput, sort: Int)

    /**
     * Removes an attribute definition from a time event type by its key.
     *
     * @param typeId the type identifier
     * @param key the attribute key to remove
     */
    suspend fun deleteTypeAttribute(typeId: String, key: String)

    /**
     * Replaces all attribute definitions on a time event type with the provided list.
     *
     * @param typeId the type identifier
     * @param attributes the complete list of attribute definitions to set
     */
    suspend fun setTypeAttributes(typeId: String, attributes: List<TemplateAttributeInput>)

    // --- Time Events ---

    /**
     * Retrieves all time events for a metadata item, ordered by start offset and sort.
     *
     * @param metadataId the metadata item identifier
     * @param metadataVersion the metadata version number
     * @return the list of time events
     */
    suspend fun getTimeEvents(metadataId: UUID, metadataVersion: Int): List<TimeEvent>

    /**
     * Retrieves time events of a specific type for a metadata item.
     *
     * @param metadataId the metadata item identifier
     * @param metadataVersion the metadata version number
     * @param type the event type identifier to filter by
     * @return the list of matching time events
     */
    suspend fun getTimeEventsByType(metadataId: UUID, metadataVersion: Int, type: String): List<TimeEvent>

    /**
     * Retrieves time events that are active at a specific millisecond offset,
     * including point events at or before the offset and range events spanning it.
     *
     * @param metadataId the metadata item identifier
     * @param metadataVersion the metadata version number
     * @param offsetMs the playback position in milliseconds
     * @return the list of active time events at this offset
     */
    suspend fun getTimeEventsAtOffset(metadataId: UUID, metadataVersion: Int, offsetMs: Long): List<TimeEvent>

    /**
     * Looks up a single time event by its unique identifier.
     *
     * @param id the time event identifier
     * @return the time event, or null if not found
     */
    suspend fun getTimeEvent(id: UUID): TimeEvent?

    /**
     * Retrieves multiple time events by their unique identifiers in a single
     * batch query. IDs that do not match any event are silently omitted.
     *
     * @param ids the time event identifiers to look up
     * @return the list of found time events
     */
    suspend fun getTimeEventsByIds(ids: List<UUID>): List<TimeEvent>

    /**
     * Creates a new time event on a metadata item's timeline.
     *
     * @param metadataId the metadata item identifier
     * @param metadataVersion the metadata version number
     * @param input the time event data
     * @return the created time event
     */
    suspend fun addTimeEvent(metadataId: UUID, metadataVersion: Int, input: TimeEventInput): TimeEvent

    /**
     * Updates an existing time event.
     *
     * @param id the time event identifier to update
     * @param input the updated time event data
     * @return the updated time event, or null if not found
     */
    suspend fun editTimeEvent(id: UUID, input: TimeEventInput): TimeEvent?

    /**
     * Deletes a time event by its unique identifier.
     *
     * @param id the time event identifier to delete
     */
    suspend fun deleteTimeEvent(id: UUID)

    /**
     * Replaces all time events on a metadata item's timeline with the provided list.
     *
     * @param metadataId the metadata item identifier
     * @param metadataVersion the metadata version number
     * @param inputs the complete list of time events to set
     * @return the created time events
     */
    suspend fun setTimeEvents(metadataId: UUID, metadataVersion: Int, inputs: List<TimeEventInput>): List<TimeEvent>

    /**
     * Deletes multiple time events by their unique identifiers in a single
     * batch operation. Returns the number of events actually deleted, which
     * may be fewer than requested if some IDs were not found.
     *
     * @param ids the time event identifiers to delete
     * @return the number of time events deleted
     */
    suspend fun deleteTimeEvents(ids: List<UUID>): Int

    /**
     * Deletes all time events of a specific type from a metadata item's timeline.
     *
     * @param metadataId the metadata item identifier
     * @param metadataVersion the metadata version number
     * @param type the event type identifier
     */
    suspend fun deleteTimeEventsByType(metadataId: UUID, metadataVersion: Int, type: String)

    // --- Time Event Metadata Relationships ---

    /**
     * Retrieves all metadata relationships for a time event.
     *
     * @param timeEventId the time event identifier
     * @return the list of metadata relationships
     */
    suspend fun getMetadataRelationships(timeEventId: UUID): List<TimeEventMetadataRelationship>

    /**
     * Creates a new metadata relationship on a time event.
     *
     * @param timeEventId the time event identifier
     * @param input the relationship data
     * @return the created relationship
     */
    suspend fun addMetadataRelationship(timeEventId: UUID, input: TimeEventMetadataRelationshipInput, notify: Boolean = true): TimeEventMetadataRelationship

    /**
     * Deletes a specific metadata relationship from a time event.
     *
     * @param timeEventId the time event identifier
     * @param metadataId the metadata item identifier
     * @param relationship the relationship type
     */
    suspend fun deleteMetadataRelationship(timeEventId: UUID, metadataId: UUID, relationship: String, notify: Boolean = true)

    /**
     * Replaces all metadata relationships on a time event with the provided list.
     *
     * @param timeEventId the time event identifier
     * @param inputs the complete list of relationships to set
     * @param notify whether to publish a change notification after the operation
     * @return the created relationships
     */
    suspend fun setMetadataRelationships(timeEventId: UUID, inputs: List<TimeEventMetadataRelationshipInput>, notify: Boolean = true): List<TimeEventMetadataRelationship>

    /**
     * Updates the attributes on an existing time event metadata relationship.
     *
     * @param timeEventId the time event identifier
     * @param metadataId the metadata item identifier
     * @param relationship the relationship type
     * @param attributes the new attributes to set
     */
    suspend fun editMetadataRelationshipAttributes(timeEventId: UUID, metadataId: UUID, relationship: String, attributes: kotlinx.serialization.json.JsonElement)

    /**
     * Deep-merges the provided attributes into the existing attributes on a time
     * event metadata relationship. Used by the image optimization pipeline to store
     * generated supplementary keys (e.g. cropped image variant references) alongside
     * the original crop coordinates without overwriting them.
     *
     * @param timeEventId the time event identifier
     * @param metadataId the metadata item identifier
     * @param relationship the relationship type
     * @param attributes the attributes to merge into the existing ones
     */
    suspend fun mergeMetadataRelationshipAttributes(timeEventId: UUID, metadataId: UUID, relationship: String, attributes: kotlinx.serialization.json.JsonElement)

    /**
     * Collects all distinct metadata IDs referenced by time event metadata relationships
     * on the given metadata item's timeline. Used during state sync to ensure that
     * metadata linked through time events (e.g. PDF page images) are published
     * alongside the parent media item.
     *
     * @param metadataId the parent metadata item identifier
     * @param metadataVersion the parent metadata version number
     * @return distinct metadata IDs linked via time event relationships
     */
    suspend fun getRelatedMetadataIds(metadataId: UUID, metadataVersion: Int): List<UUID>
}
