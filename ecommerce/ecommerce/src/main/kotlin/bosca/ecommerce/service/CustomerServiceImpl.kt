package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerExtras
import bosca.ecommerce.model.CustomerInput
import bosca.ecommerce.model.EmptyCustomerExtras
import bosca.ecommerce.repository.AccountRepository
import bosca.ecommerce.repository.CustomerRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Customers (profile-backed). All mutations are audited (attributed to the acting principal). */
@ServiceImplementation
class CustomerServiceImpl(
    private val customerRepository: CustomerRepository,
    private val accountRepository: AccountRepository,
    private val auditService: EcomAuditService,
) : CustomerService {

    override suspend fun get(id: UUID): Customer? = customerRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Customer> = customerRepository.getByIds(ids)

    override suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Customer> =
        customerRepository.getByCompany(companyId, offset, limit)

    override suspend fun getByProfile(companyId: UUID, profileId: UUID): Customer? =
        customerRepository.getByProfile(companyId, profileId)

    override suspend fun create(input: CustomerInput, profileId: UUID, principalId: UUID?): Customer = transaction {
        val customer = customerRepository.add(
            Customer(
                companyId = input.companyId,
                profileId = profileId,
                extras = input.extras ?: EmptyCustomerExtras,
            ),
        )
        auditService.record(
            entityType = "customer",
            entityId = customer.id,
            action = "created",
            serializer = Customer.serializer(),
            after = customer,
            principalId = principalId,
        )
        customer
    }

    override suspend fun editExtras(id: UUID, extras: CustomerExtras, principalId: UUID?): Customer = transaction {
        val existing = customerRepository.get(id) ?: error("customer $id not found")
        val updated = customerRepository.update(existing.copy(extras = extras)) ?: error("customer $id not found")
        auditService.record(
            entityType = "customer",
            entityId = id,
            action = "updated",
            serializer = Customer.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun setDefaultAccount(id: UUID, accountId: UUID, principalId: UUID?): Customer = transaction {
        val existing = customerRepository.get(id) ?: error("customer $id not found")
        val updated = customerRepository.update(existing.copy(defaultAccountId = accountId))
            ?: error("customer $id not found")
        auditService.record(
            entityType = "customer",
            entityId = id,
            action = "default_account_changed",
            serializer = Customer.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = customerRepository.get(id) ?: return@transaction false
        customerRepository.softDelete(id)
        auditService.record(
            entityType = "customer",
            entityId = id,
            action = "deleted",
            serializer = Customer.serializer(),
            before = existing,
            principalId = principalId,
        )
        true
    }

    override suspend fun getAccounts(customerId: UUID): List<Account> = accountRepository.getByCustomer(customerId)
}
