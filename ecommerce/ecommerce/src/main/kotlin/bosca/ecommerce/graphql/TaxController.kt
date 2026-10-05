package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Tax
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController

/** Field wiring for the `Tax` value type (per-jurisdiction breakdown; [taxes] is the line total). */
@TypeController
class TaxController : GraphQLController<Tax> {

    @Field
    fun country(source: Tax): Money = source.country

    @Field
    fun state(source: Tax): Money = source.state

    @Field
    fun city(source: Tax): Money = source.city

    @Field
    fun district(source: Tax): Money = source.district

    @Field
    fun taxes(source: Tax): Money = source.taxes
}
