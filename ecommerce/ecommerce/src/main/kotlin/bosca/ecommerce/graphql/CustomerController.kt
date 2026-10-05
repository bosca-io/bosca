package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerExtras
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.CustomerService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Customer` GraphQL type. */
@TypeController
class CustomerController(
    private val companyService: CompanyService,
    private val customerService: CustomerService,
    private val accountService: AccountService,
    private val profileService: ProfileService,
) : GraphQLController<Customer> {

    @Field
    fun id(source: Customer): UUID = source.id

    @Field
    suspend fun company(source: Customer): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    suspend fun profile(source: Customer): Profile = profileService.getById(source.profileId)

    @Field
    suspend fun defaultAccount(source: Customer): Account? =
        source.defaultAccountId?.let { accountService.get(it) }

    @Field
    suspend fun accounts(source: Customer): List<Account> = customerService.getAccounts(source.id)

    @Field
    fun extras(source: Customer): CustomerExtras = source.extras

    @Field
    fun created(source: Customer): OffsetDateTime = source.created

    @Field
    fun modified(source: Customer): OffsetDateTime = source.modified
}
