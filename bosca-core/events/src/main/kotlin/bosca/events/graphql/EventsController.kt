package bosca.events.graphql

import bosca.events.catalog.graphql.EventCatalog
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Resolves the `Events` GraphQL namespace (`Query.events`). For now it exposes the platform
 * [EventCatalog]; additional event-related queries (e.g. event history) can hang off here.
 */
@TypeController
class EventsController : GraphQLController<Events> {

    @Field
    fun catalog() = EventCatalog
}
