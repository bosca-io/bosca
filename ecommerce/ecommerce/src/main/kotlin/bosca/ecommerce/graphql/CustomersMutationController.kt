package bosca.ecommerce.graphql

import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerInput
import bosca.ecommerce.service.CustomerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Customer creates and the per-customer instance accessor under `EcomMutation.customers`.
 * Admin-gated baseline (adds customer self-service, where `profileId` defaults to the
 * caller's profile and ownership replaces the admin check).
 */
@TypeController
class CustomersMutationController(
    private val customerService: CustomerService,
    private val groups: GroupEvaluator,
) : GraphQLController<CustomersMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: CustomerInput): Customer {
        groups.verifyEcomAdmin(authentication)
        val profileId = input.profileId ?: throw IllegalArgumentException("profileId is required")
        return customerService.create(input, profileId, authentication.principal()?.id)
    }

    @Field
    fun customer(id: UUID): CustomerMutation = CustomerMutation(id)
}
