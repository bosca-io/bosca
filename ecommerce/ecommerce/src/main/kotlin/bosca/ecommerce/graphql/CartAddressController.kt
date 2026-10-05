package bosca.ecommerce.graphql

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.CartAddress
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Field wiring for the `CartAddress` GraphQL type (billing/shipping address on a cart). */
@TypeController
class CartAddressController : GraphQLController<CartAddress> {

    @Field
    fun id(source: CartAddress): UUID = source.id

    @Field
    fun type(source: CartAddress): AddressType = source.type

    @Field
    fun firstName(source: CartAddress): String = source.firstName

    @Field
    fun lastName(source: CartAddress): String = source.lastName

    @Field
    fun address1(source: CartAddress): String = source.address1

    @Field
    fun address2(source: CartAddress): String? = source.address2

    @Field
    fun city(source: CartAddress): String = source.city

    @Field
    fun state(source: CartAddress): String = source.state

    @Field
    fun country(source: CartAddress): String = source.country

    @Field
    fun zip(source: CartAddress): String = source.zip

    @Field
    fun phone(source: CartAddress): String = source.phone

    @Field
    fun email(source: CartAddress): String? = source.email

    @Field
    fun note(source: CartAddress): String? = source.note

    @Field
    fun validated(source: CartAddress): Boolean = source.validated
}
