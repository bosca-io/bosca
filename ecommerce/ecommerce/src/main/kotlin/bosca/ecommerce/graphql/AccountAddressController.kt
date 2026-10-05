package bosca.ecommerce.graphql

import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AddressType
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Field wiring for the `AccountAddress` GraphQL type. */
@TypeController
class AccountAddressController : GraphQLController<AccountAddress> {

    @Field
    fun id(source: AccountAddress): UUID = source.id

    @Field
    fun type(source: AccountAddress): AddressType = source.type

    @Field
    fun preferred(source: AccountAddress): Boolean = source.preferred

    @Field
    fun address1(source: AccountAddress): String = source.address1

    @Field
    fun address2(source: AccountAddress): String? = source.address2

    @Field
    fun city(source: AccountAddress): String = source.city

    @Field
    fun state(source: AccountAddress): String = source.state

    @Field
    fun country(source: AccountAddress): String = source.country

    @Field
    fun zip(source: AccountAddress): String = source.zip

    @Field
    fun phone(source: AccountAddress): String = source.phone

    @Field
    fun note(source: AccountAddress): String? = source.note
}
