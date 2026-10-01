package bosca.ecommerce.service

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountAddressInput
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.Money
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Billing accounts: the entity carts/payments/credit hang off. Credit changes ([addCredit]/
 * [spendCredit]) take a row lock and write audit entries; [spendCredit] never overdraws (the DB
 * also guards `credit >= 0`).
 */
interface AccountService : Service {

    /** An account by id. */
    suspend fun get(id: UUID): Account?

    /** A batch of accounts by id. */
    suspend fun getByIds(ids: List<UUID>): List<Account>

    /** A page of accounts belonging to a company, newest first. */
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Account>

    /** Create an account, attaching [AccountInput.customerIds]. */
    suspend fun create(input: AccountInput, principalId: UUID?): Account

    /** Edit the account (type/extras). */
    suspend fun edit(id: UUID, input: AccountInput, principalId: UUID?): Account

    /** Soft-delete the account. */
    suspend fun delete(id: UUID, principalId: UUID?): Boolean

    /** Add a billing/shipping address to the account. */
    suspend fun addAddress(accountId: UUID, input: AccountAddressInput, principalId: UUID?): AccountAddress

    /** The account's saved addresses. */
    suspend fun getAddresses(accountId: UUID): List<AccountAddress>

    /** Add to the account's credit balance (row-locked, audited). */
    suspend fun addCredit(accountId: UUID, amount: Money, note: String?, principalId: UUID?): Account

    /** Spend from the account's credit balance (row-locked, audited); fails if it would overdraw. */
    suspend fun spendCredit(accountId: UUID, amount: Money, note: String?, principalId: UUID?): Account

    /** Customers attached to this account. */
    suspend fun getCustomers(accountId: UUID): List<Customer>
}
