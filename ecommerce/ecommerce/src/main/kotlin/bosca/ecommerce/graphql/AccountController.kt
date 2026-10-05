package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountExtras
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.Money
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Account` (billing entity) GraphQL type. */
@TypeController
class AccountController(
    private val companyService: CompanyService,
    private val accountService: AccountService,
) : GraphQLController<Account> {

    @Field
    fun id(source: Account): UUID = source.id

    @Field
    suspend fun company(source: Account): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    fun type(source: Account): AccountType = source.type

    @Field
    fun credit(source: Account): Money = source.credit

    @Field
    suspend fun customers(source: Account): List<Customer> = accountService.getCustomers(source.id)

    @Field
    suspend fun addresses(source: Account): List<AccountAddress> = accountService.getAddresses(source.id)

    @Field
    fun extras(source: Account): AccountExtras = source.extras

    @Field
    fun created(source: Account): OffsetDateTime = source.created

    @Field
    fun modified(source: Account): OffsetDateTime = source.modified
}
