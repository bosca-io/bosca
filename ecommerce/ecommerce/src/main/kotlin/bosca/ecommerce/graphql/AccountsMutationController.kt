package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.service.AccountService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/** Account creates and the per-account instance accessor under `EcomMutation.accounts`. Admin-gated. */
@TypeController
class AccountsMutationController(
    private val accountService: AccountService,
    private val groups: GroupEvaluator,
) : GraphQLController<AccountsMutation> {

    @Field
    suspend fun add(authentication: AuthenticationContext, input: AccountInput): Account {
        groups.verifyEcomAdmin(authentication)
        return accountService.create(input, authentication.principal()?.id)
    }

    @Field
    fun account(id: UUID): AccountMutation = AccountMutation(id)
}
