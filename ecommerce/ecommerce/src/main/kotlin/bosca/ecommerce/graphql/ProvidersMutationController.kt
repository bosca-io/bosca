package bosca.ecommerce.graphql

import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.ProviderInput
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.model.ShippingProviderInput
import bosca.ecommerce.service.ProviderService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Payment/shipping provider registration under `EcomMutation.providers`. Admin-gated. */
@TypeController
class ProvidersMutationController(
    private val providerService: ProviderService,
    private val groups: GroupEvaluator,
) : GraphQLController<ProvidersMutation> {

    @Field
    suspend fun addPayment(authentication: AuthenticationContext, input: ProviderInput): PaymentProvider {
        groups.verifyEcomAdmin(authentication)
        return providerService.addPaymentProvider(input, authentication.principal()?.id)
    }

    @Field
    suspend fun addShipping(authentication: AuthenticationContext, input: ShippingProviderInput): ShippingProvider {
        groups.verifyEcomAdmin(authentication)
        return providerService.addShippingProvider(input, authentication.principal()?.id)
    }

    @Field
    suspend fun editPayment(authentication: AuthenticationContext, id: UUID, input: ProviderInput): PaymentProvider {
        groups.verifyEcomAdmin(authentication)
        return providerService.editPaymentProvider(id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun editShipping(authentication: AuthenticationContext, id: UUID, input: ShippingProviderInput): ShippingProvider {
        groups.verifyEcomAdmin(authentication)
        return providerService.editShippingProvider(id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun deletePayment(authentication: AuthenticationContext, id: UUID): Boolean {
        groups.verifyEcomAdmin(authentication)
        return providerService.deletePaymentProvider(id, authentication.principal()?.id)
    }

    @Field
    suspend fun deleteShipping(authentication: AuthenticationContext, id: UUID): Boolean {
        groups.verifyEcomAdmin(authentication)
        return providerService.deleteShippingProvider(id, authentication.principal()?.id)
    }
}
