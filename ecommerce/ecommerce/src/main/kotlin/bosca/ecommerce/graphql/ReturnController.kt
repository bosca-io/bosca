package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnLine
import bosca.ecommerce.model.ReturnStatus
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Field wiring for the `EcomReturn` GraphQL type (read view of a return/RMA). Namespaced to `EcomReturn`
 * to avoid colliding in the gateway's global type namespace, like `EcomShipment`/`EcomSubscription`.
 */
@TypeController(type = "EcomReturn")
class ReturnController : GraphQLController<Return> {
    @Field fun id(source: Return): UUID = source.id
    @Field fun cartId(source: Return): UUID = source.cartId
    @Field fun storeId(source: Return): UUID = source.storeId
    @Field fun companyId(source: Return): UUID = source.companyId
    @Field fun status(source: Return): ReturnStatus = source.status
    @Field fun reason(source: Return): String? = source.reason
    @Field fun tender(source: Return): RefundTender = source.tender
    @Field fun lines(source: Return): List<ReturnLine> = source.lines
    @Field fun refundedAmount(source: Return): Money = source.refundedAmount
    @Field fun checkNumber(source: Return): String? = source.checkNumber
    @Field fun created(source: Return): OffsetDateTime = source.created
    @Field fun modified(source: Return): OffsetDateTime = source.modified
}

/** Field wiring for the `EcomReturnLine` GraphQL type (one returned order line + quantity). */
@TypeController(type = "EcomReturnLine")
class ReturnLineController : GraphQLController<ReturnLine> {
    @Field fun itemId(source: ReturnLine): UUID = source.itemId
    @Field fun catalogProductId(source: ReturnLine): UUID = source.catalogProductId
    @Field fun quantity(source: ReturnLine): Int = source.quantity
}
