package bosca.calendar.graphql

import bosca.calendar.model.EventParticipant
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

@TypeController(type = "EventParticipant")
class EventParticipantController : GraphQLController<EventParticipant> {

    @Field
    fun eventId(participant: EventParticipant): UUID = participant.eventId

    @Field
    fun profileId(participant: EventParticipant): UUID = participant.profileId

    @Field
    fun role(participant: EventParticipant): String = participant.role

    @Field
    fun status(participant: EventParticipant): String = participant.status

    @Field
    fun created(participant: EventParticipant): OffsetDateTime = participant.created
}
