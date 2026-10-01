package bosca.content.timeevent.service

import bosca.attributes.TemplateAttributeInput
import bosca.content.attributes.model.TemplateAttribute
import bosca.content.attributes.model.TemplateTool
import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventInput
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventMetadataRelationshipInput
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.model.TimeEventTypeAttribute
import bosca.content.timeevent.model.TimeEventTypeInput
import bosca.content.timeevent.events.TIME_EVENT_CHANGED_CHANNEL
import bosca.content.timeevent.events.TimeEventChanged
import bosca.content.timeevent.repository.TimeEventMetadataRelationshipRepository
import bosca.content.timeevent.repository.TimeEventRepository
import bosca.content.timeevent.repository.TimeEventTypeAttributeRepository
import bosca.db.transaction
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Default implementation of [TimeEventService] backed by SQL repositories.
 * Publishes change notifications via [PubSubService] after mutations so that
 * connected clients receive real-time updates through GraphQL subscriptions.
 */
@ServiceImplementation
class TimeEventServiceImpl(
    private val repository: TimeEventRepository,
    private val attributeRepository: TimeEventTypeAttributeRepository,
    private val relationshipRepository: TimeEventMetadataRelationshipRepository,
    private val pubSubService: PubSubService,
    private val json: Json
) : TimeEventService {

    // --- Time Event Types ---

    override suspend fun getTypes(): List<TimeEventType> = repository.getTypes()

    override suspend fun getType(id: String): TimeEventType? = repository.getType(id)

    override suspend fun addType(type: TimeEventTypeInput): TimeEventType =
        repository.addType(
            id = type.id,
            name = type.name,
            description = type.description,
            schema = type.schema,
            configuration = type.configuration ?: JsonObject(emptyMap())
        )

    override suspend fun editType(id: String, type: TimeEventTypeInput): TimeEventType? =
        repository.updateType(
            id = id,
            name = type.name,
            description = type.description,
            schema = type.schema,
            configuration = type.configuration ?: JsonObject(emptyMap())
        )

    override suspend fun deleteType(id: String) = repository.deleteType(id)

    // --- Time Event Type Attributes ---

    override suspend fun getTypeAttributes(typeId: String): List<TemplateAttribute> =
        attributeRepository.getByTypeId(typeId)
            .map { TemplateAttribute(timeEventTypeAttribute = it) }

    override suspend fun addTypeAttribute(typeId: String, attribute: TemplateAttributeInput, sort: Int) {
        transaction {
            attributeRepository.add(attribute.toTimeEventTypeAttribute(typeId, sort))
        }
    }

    override suspend fun deleteTypeAttribute(typeId: String, key: String) {
        transaction {
            attributeRepository.deleteByTypeIdAndKey(typeId, key)
        }
    }

    override suspend fun setTypeAttributes(typeId: String, attributes: List<TemplateAttributeInput>) {
        transaction {
            attributeRepository.deleteByTypeId(typeId)
            attributes.forEachIndexed { index, attribute ->
                attributeRepository.add(attribute.toTimeEventTypeAttribute(typeId, index))
            }
        }
    }

    private fun TemplateAttributeInput.toTimeEventTypeAttribute(typeId: String, sort: Int) =
        TimeEventTypeAttribute(
            typeId = typeId,
            key = key,
            name = name,
            description = description,
            supplementaryKey = supplementaryKey,
            configuration = configuration,
            type = type,
            ui = ui,
            list = list,
            sort = sort,
            tools = tools?.map {
                TemplateTool(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    query = it.query,
                    resultPath = it.resultPath
                )
            }?.let { json.encodeToJsonElement(it) }
        )

    // --- Time Events ---

    override suspend fun getTimeEvents(metadataId: UUID, metadataVersion: Int): List<TimeEvent> =
        repository.getTimeEvents(metadataId, metadataVersion)

    override suspend fun getTimeEventsByType(metadataId: UUID, metadataVersion: Int, type: String): List<TimeEvent> =
        repository.getTimeEventsByType(metadataId, metadataVersion, type)

    override suspend fun getTimeEventsAtOffset(metadataId: UUID, metadataVersion: Int, offsetMs: Long): List<TimeEvent> =
        repository.getTimeEventsAtOffset(metadataId, metadataVersion, offsetMs)

    override suspend fun getTimeEvent(id: UUID): TimeEvent? = repository.getTimeEvent(id)

    override suspend fun addTimeEvent(metadataId: UUID, metadataVersion: Int, input: TimeEventInput): TimeEvent {
        require(input.startOffsetMs >= 0) { "startOffsetMs must be non-negative" }
        val endOffsetMs = input.endOffsetMs
        if (endOffsetMs != null) {
            require(endOffsetMs >= input.startOffsetMs) { "endOffsetMs must be >= startOffsetMs" }
        }
        return repository.addTimeEvent(
            metadataId = metadataId,
            metadataVersion = metadataVersion,
            type = input.type,
            startOffsetMs = input.startOffsetMs,
            endOffsetMs = input.endOffsetMs,
            sort = input.sort ?: 0,
            attributes = input.attributes ?: JsonObject(emptyMap())
        )
    }

    override suspend fun editTimeEvent(id: UUID, input: TimeEventInput): TimeEvent? {
        require(input.startOffsetMs >= 0) { "startOffsetMs must be non-negative" }
        val endOffsetMs = input.endOffsetMs
        if (endOffsetMs != null) {
            require(endOffsetMs >= input.startOffsetMs) { "endOffsetMs must be >= startOffsetMs" }
        }
        return repository.updateTimeEvent(
            id = id,
            type = input.type,
            startOffsetMs = input.startOffsetMs,
            endOffsetMs = input.endOffsetMs,
            sort = input.sort ?: 0,
            attributes = input.attributes ?: JsonObject(emptyMap())
        )
    }

    override suspend fun deleteTimeEvent(id: UUID) = repository.deleteTimeEvent(id)

    override suspend fun getTimeEventsByIds(ids: List<UUID>): List<TimeEvent> =
        repository.getTimeEventsByIds(ids)

    override suspend fun deleteTimeEvents(ids: List<UUID>): Int {
        if (ids.isEmpty()) return 0
        repository.deleteTimeEventsByIds(ids)
        return ids.size
    }

    override suspend fun setTimeEvents(metadataId: UUID, metadataVersion: Int, inputs: List<TimeEventInput>): List<TimeEvent> {
        inputs.forEach { input ->
            require(input.startOffsetMs >= 0) { "startOffsetMs must be non-negative" }
            val endOffsetMs = input.endOffsetMs
            if (endOffsetMs != null) {
                require(endOffsetMs >= input.startOffsetMs) { "endOffsetMs must be >= startOffsetMs" }
            }
        }
        val result = transaction {
            repository.deleteAllTimeEvents(metadataId, metadataVersion)
            inputs.map { input ->
                repository.addTimeEvent(
                    metadataId = metadataId,
                    metadataVersion = metadataVersion,
                    type = input.type,
                    startOffsetMs = input.startOffsetMs,
                    endOffsetMs = input.endOffsetMs,
                    sort = input.sort ?: 0,
                    attributes = input.attributes ?: JsonObject(emptyMap())
                )
            }
        }
        pubSubService.publish(
            TIME_EVENT_CHANGED_CHANNEL,
            TimeEventChanged.serializer(),
            TimeEventChanged(
                metadataId = metadataId,
                metadataVersion = metadataVersion,
                eventCount = inputs.size,
            )
        )
        return result
    }

    override suspend fun deleteTimeEventsByType(metadataId: UUID, metadataVersion: Int, type: String) =
        repository.deleteTimeEventsByType(metadataId, metadataVersion, type)

    // --- Time Event Metadata Relationships ---

    override suspend fun getMetadataRelationships(timeEventId: UUID): List<TimeEventMetadataRelationship> =
        relationshipRepository.getByTimeEventId(timeEventId)

    override suspend fun addMetadataRelationship(
        timeEventId: UUID,
        input: TimeEventMetadataRelationshipInput,
        notify: Boolean,
    ): TimeEventMetadataRelationship {
        val result = relationshipRepository.add(
            TimeEventMetadataRelationship(
                timeEventId = timeEventId,
                metadataId = input.metadataId,
                metadataVersion = input.metadataVersion,
                relationship = input.relationship,
                attributes = input.attributes
            )
        )
        if (notify) publishTimeEventChanged(timeEventId)
        return result
    }

    override suspend fun deleteMetadataRelationship(timeEventId: UUID, metadataId: UUID, relationship: String, notify: Boolean) {
        relationshipRepository.delete(timeEventId, metadataId, relationship)
        if (notify) publishTimeEventChanged(timeEventId)
    }

    override suspend fun setMetadataRelationships(
        timeEventId: UUID,
        inputs: List<TimeEventMetadataRelationshipInput>,
        notify: Boolean,
    ): List<TimeEventMetadataRelationship> {
        val result = transaction {
            relationshipRepository.deleteByTimeEventId(timeEventId)
            inputs.map { input ->
                relationshipRepository.add(
                    TimeEventMetadataRelationship(
                        timeEventId = timeEventId,
                        metadataId = input.metadataId,
                        metadataVersion = input.metadataVersion,
                        relationship = input.relationship,
                        attributes = input.attributes
                    )
                )
            }
        }
        if (notify) publishTimeEventChanged(timeEventId)
        return result
    }

    override suspend fun editMetadataRelationshipAttributes(
        timeEventId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ) {
        relationshipRepository.setAttributes(timeEventId, metadataId, relationship, attributes)
        publishTimeEventChanged(timeEventId)
    }

    override suspend fun mergeMetadataRelationshipAttributes(
        timeEventId: UUID,
        metadataId: UUID,
        relationship: String,
        attributes: JsonElement
    ) = transaction {
        if (attributes !is JsonObject) error("must be an object")
        val existing = relationshipRepository.getAttributes(timeEventId, metadataId, relationship)
            ?.takeIf { it is JsonObject } ?: JsonObject(emptyMap())
        val merged = JsonObject(existing.jsonObject + attributes.jsonObject)
        if (existing == merged) return@transaction
        relationshipRepository.setAttributes(timeEventId, metadataId, relationship, merged)
    }

    override suspend fun getRelatedMetadataIds(metadataId: UUID, metadataVersion: Int): List<UUID> {
        val timeEvents = repository.getTimeEvents(metadataId, metadataVersion)
        if (timeEvents.isEmpty()) return emptyList()
        val relationships = relationshipRepository.getByTimeEventIds(timeEvents.map { it.id })
        return relationships.map { it.metadataId }.distinct()
    }

    private suspend fun publishTimeEventChanged(timeEventId: UUID, eventCount: Int = 1) {
        val timeEvent = repository.getTimeEvent(timeEventId) ?: return
        pubSubService.publish(
            TIME_EVENT_CHANGED_CHANNEL,
            TimeEventChanged.serializer(),
            TimeEventChanged(
                metadataId = timeEvent.metadataId,
                metadataVersion = timeEvent.metadataVersion,
                eventCount = eventCount,
            )
        )
    }
}
