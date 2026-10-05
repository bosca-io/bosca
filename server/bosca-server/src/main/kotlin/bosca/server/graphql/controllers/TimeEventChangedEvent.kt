package bosca.server.graphql.controllers

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * GraphQL representation of a time event change notification, emitted
 * when a metadata item's timeline events are modified by a background process
 * such as PDF import.
 */
@Serializable
data class TimeEventChangedEvent(
    @Contextual
    val metadataId: UUID,
    val metadataVersion: Int,
    val eventCount: Int,
)

/**
 * Resolves fields on the [TimeEventChangedEvent] GraphQL type, delegating
 * each field to the corresponding property on the serialized event.
 */
@TypeController
class TimeEventChangedEventController : GraphQLController<TimeEventChangedEvent> {

    @Field
    fun metadataId(event: TimeEventChangedEvent) = event.metadataId

    @Field
    fun metadataVersion(event: TimeEventChangedEvent) = event.metadataVersion

    @Field
    fun eventCount(event: TimeEventChangedEvent) = event.eventCount
}
