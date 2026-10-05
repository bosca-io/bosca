package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.ProviderConfiguration
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Field wiring for the `ShippingProvider` GraphQL type. [configuration] is admin-gated (may hold
 * provider credentials).
 */
@TypeController
class ShippingProviderController(
    private val companyService: CompanyService,
    private val groups: GroupEvaluator,
) : GraphQLController<ShippingProvider> {

    @Field
    fun id(source: ShippingProvider): UUID = source.id

    @Field
    suspend fun company(source: ShippingProvider): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    fun name(source: ShippingProvider): String = source.name

    @Field
    fun key(source: ShippingProvider): String = source.key

    @Field
    fun providerKey(source: ShippingProvider): String = source.providerKey

    @Field
    fun configuration(authentication: AuthenticationContext, source: ShippingProvider): ProviderConfiguration {
        groups.verifyEcomAdmin(authentication)
        return source.configuration
    }
}
