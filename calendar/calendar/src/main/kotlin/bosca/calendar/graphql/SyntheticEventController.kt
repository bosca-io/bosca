package bosca.calendar.graphql

import bosca.calendar.repository.SyntheticEvent
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Pairs a [SyntheticEvent] with the module it was projected from so the
 * GraphQL controller can expose the source discriminator alongside the
 * event's scalar fields.
 */
data class SyntheticEventAndSource(
    val event: SyntheticEvent,
    val source: SyntheticEventSource,
)

enum class SyntheticEventSource { CAMPAIGN, SCHEDULED_JOB, SCHEDULED_PUBLISH }

@TypeController(type = "SyntheticEvent")
class SyntheticEventController : GraphQLController<SyntheticEventAndSource> {

    @Field
    fun id(wrapper: SyntheticEventAndSource): UUID = wrapper.event.id

    @Field
    fun source(wrapper: SyntheticEventAndSource): SyntheticEventSource = wrapper.source

    @Field
    fun title(wrapper: SyntheticEventAndSource): String = wrapper.event.title

    @Field
    fun description(wrapper: SyntheticEventAndSource): String = wrapper.event.description

    @Field
    fun startsAt(wrapper: SyntheticEventAndSource): OffsetDateTime = wrapper.event.startsAt

    @Field
    fun endsAt(wrapper: SyntheticEventAndSource): OffsetDateTime = wrapper.event.endsAt

    @Field
    fun completed(wrapper: SyntheticEventAndSource): Boolean = wrapper.event.completed
}
