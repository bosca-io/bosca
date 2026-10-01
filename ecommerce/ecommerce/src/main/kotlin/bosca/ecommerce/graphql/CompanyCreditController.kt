package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.Money
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CompanyService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `CompanyCredit` GraphQL type. */
@TypeController
class CompanyCreditController(
    private val companyService: CompanyService,
    private val accountService: AccountService,
) : GraphQLController<CompanyCredit> {

    @Field
    fun id(source: CompanyCredit): UUID = source.id

    @Field
    suspend fun company(source: CompanyCredit): Company =
        companyService.get(source.companyId) ?: error("company ${source.companyId} not found")

    @Field
    suspend fun account(source: CompanyCredit): Account? = source.accountId?.let { accountService.get(it) }

    @Field
    fun number(source: CompanyCredit): String = source.number

    @Field
    fun description(source: CompanyCredit): String? = source.description

    @Field
    fun balance(source: CompanyCredit): Money = source.balance

    @Field
    fun paid(source: CompanyCredit): Money = source.paid

    @Field
    fun expires(source: CompanyCredit): OffsetDateTime? = source.expires

    @Field
    fun created(source: CompanyCredit): OffsetDateTime = source.created
}
