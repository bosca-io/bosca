package bosca.content.timeevent.graphql

import bosca.attributes.TemplateAttributeInput
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.model.TimeEventTypeInput
import bosca.content.timeevent.service.TimeEventService
import bosca.content.timeevent.jobs.PdfTimelineImportJob
import bosca.content.timeevent.jobs.enqueue
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonElement

object TimeEventMutation

@TypeController
class TimeEventMutationController(
    private val timeEventService: TimeEventService,
    private val metadataService: MetadataService,
    private val permissionEvaluator: MetadataPermissionEvaluator,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<TimeEventMutation> {

    // Time event types (admin only)

    @Field
    suspend fun addType(authentication: AuthenticationContext, type: TimeEventTypeInput): TimeEventType {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return timeEventService.addType(type)
    }

    @Field
    suspend fun editType(authentication: AuthenticationContext, id: String, type: TimeEventTypeInput): TimeEventType {
        groupEvaluator.verifyHasAdminGroup(authentication)
        return timeEventService.editType(id, type) ?: error("Time event type not found: $id")
    }

    @Field
    suspend fun deleteType(authentication: AuthenticationContext, id: String): Boolean {
        groupEvaluator.verifyHasAdminGroup(authentication)
        timeEventService.deleteType(id)
        return true
    }

    // Time event type attributes (admin only)

    @Field
    suspend fun addTypeAttribute(
        authentication: AuthenticationContext,
        typeId: String,
        attribute: TemplateAttributeInput,
        sort: Int
    ): TimeEventType {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val type = timeEventService.getType(typeId) ?: error("Time event type not found: $typeId")
        timeEventService.addTypeAttribute(typeId, attribute, sort)
        return type
    }

    @Field
    suspend fun deleteTypeAttribute(
        authentication: AuthenticationContext,
        typeId: String,
        key: String
    ): TimeEventType {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val type = timeEventService.getType(typeId) ?: error("Time event type not found: $typeId")
        timeEventService.deleteTypeAttribute(typeId, key)
        return type
    }

    @Field
    suspend fun setTypeAttributes(
        authentication: AuthenticationContext,
        typeId: String,
        attributes: List<TemplateAttributeInput>
    ): TimeEventType {
        groupEvaluator.verifyHasAdminGroup(authentication)
        val type = timeEventService.getType(typeId) ?: error("Time event type not found: $typeId")
        timeEventService.setTypeAttributes(typeId, attributes)
        return type
    }

    // Time events

    @Field
    suspend fun add(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        timeEvent: TimeEventInput
    ): TimeEvent {
        verifyMetadataEdit(authentication, metadataId)
        return timeEventService.addTimeEvent(metadataId, metadataVersion, timeEvent)
    }

    @Field
    suspend fun edit(authentication: AuthenticationContext, id: UUID, timeEvent: TimeEventInput): TimeEvent {
        val existing = timeEventService.getTimeEvent(id) ?: error("Time event not found: $id")
        verifyMetadataEdit(authentication, existing.metadataId)
        return timeEventService.editTimeEvent(id, timeEvent) ?: error("Time event not found: $id")
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, id: UUID): Boolean {
        val existing = timeEventService.getTimeEvent(id) ?: error("Time event not found: $id")
        verifyMetadataEdit(authentication, existing.metadataId)
        timeEventService.deleteTimeEvent(id)
        return true
    }

    /**
     * Deletes multiple time events by their unique IDs in a single batch
     * operation. Each event's parent metadata is checked for EDIT permission.
     * Events that no longer exist are silently skipped.
     *
     * @return the number of time events actually deleted
     */
    @Field
    suspend fun deleteAll(authentication: AuthenticationContext, ids: List<UUID>): Int {
        val events = timeEventService.getTimeEventsByIds(ids)
        for (event in events) {
            verifyMetadataEdit(authentication, event.metadataId)
        }
        return timeEventService.deleteTimeEvents(events.map { it.id })
    }

    // Batch operations

    @Field
    suspend fun setTimeEvents(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        timeEvents: List<TimeEventInput>
    ): List<TimeEvent> {
        verifyMetadataEdit(authentication, metadataId)
        return timeEventService.setTimeEvents(metadataId, metadataVersion, timeEvents)
    }

    @Field
    suspend fun deleteTimeEventsByType(
        authentication: AuthenticationContext,
        metadataId: UUID,
        metadataVersion: Int,
        type: String
    ): Boolean {
        verifyMetadataEdit(authentication, metadataId)
        timeEventService.deleteTimeEventsByType(metadataId, metadataVersion, type)
        return true
    }

    // Metadata relationships

    @Field
    suspend fun addMetadataRelationship(
        authentication: AuthenticationContext,
        timeEventId: UUID,
        relationship: TimeEventMetadataRelationshipInput
    ): TimeEventMetadataRelationship {
        verifyTimeEventEdit(authentication, timeEventId)
        verifyMetadataView(authentication, relationship.metadataId)
        return timeEventService.addMetadataRelationship(timeEventId, relationship)
    }

    @Field
    suspend fun deleteMetadataRelationship(
        authentication: AuthenticationContext,
        timeEventId: UUID,
        metadataId: UUID,
        relationship: String
    ): Boolean {
        verifyTimeEventEdit(authentication, timeEventId)
        timeEventService.deleteMetadataRelationship(timeEventId, metadataId, relationship)
        return true
    }

    @Field
    suspend fun setMetadataRelationships(
        authentication: AuthenticationContext,
        timeEventId: UUID,
        relationships: List<TimeEventMetadataRelationshipInput>
    ): List<TimeEventMetadataRelationship> {
        verifyTimeEventEdit(authentication, timeEventId)
        for (relationship in relationships) {
            verifyMetadataView(authentication, relationship.metadataId)
        }
        return timeEventService.setMetadataRelationships(timeEventId, relationships)
    }

    @Field
    suspend fun editMetadataRelationshipAttributes(
        authentication: AuthenticationContext,
        timeEventId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ): Boolean {
        verifyTimeEventEdit(authentication, timeEventId)
        timeEventService.editMetadataRelationshipAttributes(timeEventId, metadataId, relationship, attributes)
        return true
    }

    // PDF import

    @Field
    suspend fun importPdfAsTimelineEvents(
        authentication: AuthenticationContext,
        pdfMetadataId: UUID,
        targetMetadataId: UUID,
        targetMetadataVersion: Int,
        eventTypeId: String,
        relationship: String,
        durationMs: Long,
    ): Boolean {
        require(durationMs > 0) { "durationMs must be positive" }
        verifyMetadataEdit(authentication, targetMetadataId)
        val pdfMetadata = metadataService.getById(pdfMetadataId) ?: error("PDF metadata not found: $pdfMetadataId")
        require(pdfMetadata.uploaded != null) { "PDF has not been uploaded yet" }
        permissionEvaluator.verifyAllowed(authentication, pdfMetadata, PermissionAction.VIEW)
        timeEventService.getType(eventTypeId) ?: error("Time event type not found: $eventTypeId")

        PdfTimelineImportJob(
            pdfMetadataId = pdfMetadataId,
            targetMetadataId = targetMetadataId,
            targetMetadataVersion = targetMetadataVersion,
            eventTypeId = eventTypeId,
            relationship = relationship,
            durationMs = durationMs,
        ).enqueue()

        return true
    }

    private suspend fun verifyTimeEventEdit(authentication: AuthenticationContext, timeEventId: UUID) {
        val existing = timeEventService.getTimeEvent(timeEventId) ?: error("Time event not found: $timeEventId")
        verifyMetadataEdit(authentication, existing.metadataId)
    }

    private suspend fun verifyMetadataEdit(authentication: AuthenticationContext, metadataId: UUID) {
        val metadata = metadataService.getById(metadataId) ?: error("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.EDIT)
    }

    private suspend fun verifyMetadataView(authentication: AuthenticationContext, metadataId: UUID) {
        val metadata = metadataService.getById(metadataId) ?: error("Metadata not found: $metadataId")
        permissionEvaluator.verifyAllowed(authentication, metadata, PermissionAction.VIEW)
    }
}
