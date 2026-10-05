package bosca.ecommerce.graphql

import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerExtras
import bosca.ecommerce.service.CustomerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one customer (the id carried by [CustomerMutation]). Admin-gated baseline. */
@TypeController
class CustomerMutationController(
    private val customerService: CustomerService,
    private val groups: GroupEvaluator,
) : GraphQLController<CustomerMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: CustomerMutation, extras: CustomerExtras): Customer {
        groups.verifyEcomAdmin(authentication)
        return customerService.editExtras(source.id, extras, authentication.principal()?.id)
    }

    @Field
    suspend fun setDefaultAccount(
        authentication: AuthenticationContext,
        source: CustomerMutation,
        accountId: bosca.serialization.UUID,
    ): Customer {
        groups.verifyEcomAdmin(authentication)
        return customerService.setDefaultAccount(source.id, accountId, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: CustomerMutation): Boolean {
        groups.verifyEcomAdmin(authentication)
        return customerService.delete(source.id, authentication.principal()?.id)
    }
}
