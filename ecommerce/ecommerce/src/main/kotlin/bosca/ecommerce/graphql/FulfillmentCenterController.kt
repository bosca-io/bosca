package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.ProviderService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Field wiring for the `FulfillmentCenter` GraphQL type. */
@TypeController
class FulfillmentCenterController(
    private val companyService: CompanyService,
    private val providerService: ProviderService,
) : GraphQLController<FulfillmentCenter> {

    @Field
    fun id(source: FulfillmentCenter): UUID = source.id

    @Field
    suspend fun company(source: FulfillmentCenter): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    fun name(source: FulfillmentCenter): String = source.name

    @Field
    fun connectorKey(source: FulfillmentCenter): String = source.connectorKey

    @Field
    suspend fun shippingProvider(source: FulfillmentCenter): ShippingProvider? =
        providerService.getShippingProvider(source.shippingProviderId)

    @Field
    fun address1(source: FulfillmentCenter): String = source.address1

    @Field
    fun address2(source: FulfillmentCenter): String? = source.address2

    @Field
    fun city(source: FulfillmentCenter): String = source.city

    @Field
    fun state(source: FulfillmentCenter): String = source.state

    @Field
    fun country(source: FulfillmentCenter): String = source.country

    @Field
    fun zip(source: FulfillmentCenter): String = source.zip
}
