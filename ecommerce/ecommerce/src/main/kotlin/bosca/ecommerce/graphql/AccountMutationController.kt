package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountAddressInput
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.model.Money
import bosca.ecommerce.service.AccountService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator

/** Mutations scoped to one account (the id carried by [AccountMutation]). Admin-gated. */
@TypeController
class AccountMutationController(
    private val accountService: AccountService,
    private val groups: GroupEvaluator,
) : GraphQLController<AccountMutation> {

    @Field
    suspend fun edit(authentication: AuthenticationContext, source: AccountMutation, input: AccountInput): Account {
        groups.verifyEcomAdmin(authentication)
        return accountService.edit(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun delete(authentication: AuthenticationContext, source: AccountMutation): Boolean {
        groups.verifyEcomAdmin(authentication)
        return accountService.delete(source.id, authentication.principal()?.id)
    }

    @Field
    suspend fun addAddress(
        authentication: AuthenticationContext,
        source: AccountMutation,
        input: AccountAddressInput,
    ): AccountAddress {
        groups.verifyEcomAdmin(authentication)
        return accountService.addAddress(source.id, input, authentication.principal()?.id)
    }

    @Field
    suspend fun addCredit(
        authentication: AuthenticationContext,
        source: AccountMutation,
        amount: Money,
        note: String?,
    ): Account {
        groups.verifyEcomAdmin(authentication)
        return accountService.addCredit(source.id, amount, note, authentication.principal()?.id)
    }
}
