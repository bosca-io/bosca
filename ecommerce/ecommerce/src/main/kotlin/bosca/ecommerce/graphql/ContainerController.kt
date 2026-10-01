package bosca.ecommerce.graphql

import bosca.ecommerce.model.Container
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Field wiring for the `EcomContainer` GraphQL type (a box type in a company's packing catalog).
 * Namespaced to `EcomContainer` to avoid colliding in the gateway's global type namespace.
 */
@TypeController(type = "EcomContainer")
class ContainerController : GraphQLController<Container> {
    @Field fun id(source: Container): UUID = source.id
    @Field fun companyId(source: Container): UUID = source.companyId
    @Field fun name(source: Container): String = source.name
    @Field fun width(source: Container): Double = source.width
    @Field fun height(source: Container): Double = source.height
    @Field fun length(source: Container): Double = source.length
    @Field fun weight(source: Container): Double = source.weight
    @Field fun supportedWidth(source: Container): Double = source.supportedWidth
    @Field fun supportedHeight(source: Container): Double = source.supportedHeight
    @Field fun supportedLength(source: Container): Double = source.supportedLength
    @Field fun supportedWeight(source: Container): Double = source.supportedWeight
    @Field fun created(source: Container): OffsetDateTime = source.created
    @Field fun modified(source: Container): OffsetDateTime = source.modified
}
