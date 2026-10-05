package bosca.content.ordering.graphql

import bosca.attributes.AttributeLocation
import bosca.attributes.AttributeType
import bosca.content.ordering.Order
import bosca.content.ordering.Ordering
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

@TypeController
class OrderingController : GraphQLController<Ordering> {

    @Field
    fun field(ordering: Ordering): String? = ordering.field

    @Field
    fun location(ordering: Ordering): AttributeLocation? = ordering.location

    @Field
    fun order(ordering: Ordering): Order? = ordering.order

    @Field
    fun path(ordering: Ordering): List<String>? = ordering.path

    @Field
    fun type(ordering: Ordering): AttributeType? = ordering.type
}
