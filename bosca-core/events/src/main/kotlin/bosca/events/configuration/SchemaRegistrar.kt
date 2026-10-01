package bosca.events.configuration

import bosca.graphql.annotations.Schema
import bosca.graphql.annotations.Schemas

/**
 * Declares the GraphQL schema files contributed by the events module. KSP processes this
 * interface to generate `EventsSchemaRegistrar`, which the server merges into the runtime schema.
 */
@Schemas
interface SchemaRegistrar {

    /** Schema defining the platform Event Catalog query surface (`Query.events`). */
    @Schema("eventcatalog.graphqls")
    val eventCatalog: String
}
