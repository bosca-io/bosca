package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ProviderConfiguration
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Field wiring for the `PaymentProvider` GraphQL type. [configuration] may hold provider secrets, so
 * it is admin-gated — never readable on storefront paths.
 */
@TypeController
class PaymentProviderController(
    private val companyService: CompanyService,
    private val groups: GroupEvaluator,
) : GraphQLController<PaymentProvider> {

    @Field
    fun id(source: PaymentProvider): UUID = source.id

    @Field
    suspend fun company(source: PaymentProvider): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    fun name(source: PaymentProvider): String = source.name

    @Field
    fun providerKey(source: PaymentProvider): String = source.providerKey

    @Field
    fun configuration(authentication: AuthenticationContext, source: PaymentProvider): ProviderConfiguration {
        groups.verifyEcomAdmin(authentication)
        return source.configuration
    }
}
