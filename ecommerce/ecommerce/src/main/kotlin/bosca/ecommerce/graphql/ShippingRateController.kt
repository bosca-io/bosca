package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ShippingRate
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Field wiring for the `ShippingRate` GraphQL type (a quoted rate option). */
@TypeController
class ShippingRateController : GraphQLController<ShippingRate> {

    @Field
    fun carrier(source: ShippingRate): String = source.carrier

    @Field
    fun serviceLevel(source: ShippingRate): String = source.serviceLevel

    @Field
    fun durationTerms(source: ShippingRate): String? = source.durationTerms

    @Field
    fun token(source: ShippingRate): String = source.token

    @Field
    fun amount(source: ShippingRate): Money = source.amount
}
