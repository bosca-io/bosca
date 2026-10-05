package bosca.events.catalog.graphql

import bosca.events.catalog.EventDescriptor
import bosca.events.catalog.EventField
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/**
 * Field wiring for the `EventDescriptor` GraphQL type. Every GraphQL object type needs a
 * controller so the schema has a data fetcher per field — a plain data class is not resolved
 * automatically.
 */
@TypeController
class EventDescriptorController : GraphQLController<EventDescriptor> {

    @Field
    fun fqdn(source: EventDescriptor): String = source.fqdn

    @Field
    fun displayName(source: EventDescriptor): String = source.displayName

    @Field
    fun description(source: EventDescriptor): String = source.description

    @Field
    fun pubsubChannel(source: EventDescriptor): String? = source.pubsubChannel

    @Field
    fun jobNames(source: EventDescriptor): List<String> = source.jobNames

    @Field
    fun fields(source: EventDescriptor): List<EventField> = source.fields
}
