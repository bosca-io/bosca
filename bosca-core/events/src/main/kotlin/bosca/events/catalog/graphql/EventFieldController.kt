package bosca.events.catalog.graphql

import bosca.events.catalog.EventField
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Field wiring for the `EventField` GraphQL type (the filterable fields on an event payload). */
@TypeController
class EventFieldController : GraphQLController<EventField> {

    @Field
    fun name(source: EventField): String = source.name

    @Field
    fun type(source: EventField): String = source.type
}
