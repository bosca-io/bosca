package bosca.ecommerce.service

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerExtras
import bosca.ecommerce.model.CustomerInput
import bosca.serialization.UUID
import bosca.service.Service

/** Shoppers (profile-backed). Resolution is principal -> profile -> customer via the auth context. */
interface CustomerService : Service {

    /** A customer by id. */
    suspend fun get(id: UUID): Customer?

    /** Batch-load customers by id. */
    suspend fun getByIds(ids: List<UUID>): List<Customer>

    /** A page of customers in a company, newest first (admin surface — PII). */
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Customer>

    /** The customer for a profile within a company (the `myCustomer` resolution target). */
    suspend fun getByProfile(companyId: UUID, profileId: UUID): Customer?

    /** Create a customer linked to [CustomerInput.profileId] (resolved by the caller when null). */
    suspend fun create(input: CustomerInput, profileId: UUID, principalId: UUID?): Customer

    /** Replace the customer's typed extras. */
    suspend fun editExtras(id: UUID, extras: CustomerExtras, principalId: UUID?): Customer

    /** Set which of the customer's accounts is the default for new carts/orders. */
    suspend fun setDefaultAccount(id: UUID, accountId: UUID, principalId: UUID?): Customer

    /** Soft-delete the customer. */
    suspend fun delete(id: UUID, principalId: UUID?): Boolean

    /** All accounts this customer belongs to. */
    suspend fun getAccounts(customerId: UUID): List<Account>
}
