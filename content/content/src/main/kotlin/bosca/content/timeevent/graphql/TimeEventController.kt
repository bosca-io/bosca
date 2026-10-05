package bosca.content.timeevent.graphql

import bosca.content.timeevent.model.TimeEvent
import bosca.content.timeevent.model.TimeEventMetadataRelationship
import bosca.content.timeevent.model.TimeEventType
import bosca.content.timeevent.service.TimeEventService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * GraphQL controller that resolves fields on the [TimeEvent] type,
 * including computed properties like duration and lazy-loaded
 * associations such as the event type and metadata relationships.
 */
@TypeController
class TimeEventController(
    private val timeEventService: TimeEventService
) : GraphQLController<TimeEvent> {

    @Field
    fun id(timeEvent: TimeEvent) = timeEvent.id

    @Field
    fun metadataId(timeEvent: TimeEvent) = timeEvent.metadataId

    @Field
    fun metadataVersion(timeEvent: TimeEvent) = timeEvent.metadataVersion

    @Field
    suspend fun type(timeEvent: TimeEvent): TimeEventType {
        return timeEventService.getType(timeEvent.type)
            ?: error("Unknown time event type: ${timeEvent.type}")
    }

    @Field
    fun startOffsetMs(timeEvent: TimeEvent) = timeEvent.startOffsetMs

    @Field
    fun endOffsetMs(timeEvent: TimeEvent) = timeEvent.endOffsetMs

    @Field
    fun sort(timeEvent: TimeEvent) = timeEvent.sort

    @Field
    fun attributes(timeEvent: TimeEvent) = timeEvent.attributes

    @Field
    fun created(timeEvent: TimeEvent) = timeEvent.created

    @Field
    fun modified(timeEvent: TimeEvent) = timeEvent.modified

    @Field
    fun durationMs(timeEvent: TimeEvent): Long? {
        return timeEvent.endOffsetMs?.let { it - timeEvent.startOffsetMs }
    }

    @Field
    suspend fun metadataRelationships(timeEvent: TimeEvent, filter: List<String>?): List<TimeEventMetadataRelationship> {
        val relationships = timeEventService.getMetadataRelationships(timeEvent.id)
        if (filter.isNullOrEmpty()) return relationships
        return relationships.filter { it.relationship in filter }
    }
}
