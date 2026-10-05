package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountAddressInput
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.EmptyAccountExtras
import bosca.ecommerce.model.Money
import bosca.ecommerce.repository.AccountAddressRepository
import bosca.ecommerce.repository.AccountCustomerRepository
import bosca.ecommerce.repository.AccountRepository
import bosca.ecommerce.repository.CustomerRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Billing accounts, addresses, the customer m2m, and credit balance changes (row-locked, audited). */
@ServiceImplementation
class AccountServiceImpl(
    private val accountRepository: AccountRepository,
    private val accountCustomerRepository: AccountCustomerRepository,
    private val accountAddressRepository: AccountAddressRepository,
    private val customerRepository: CustomerRepository,
    private val auditService: EcomAuditService,
) : AccountService {

    override suspend fun get(id: UUID): Account? = accountRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Account> = accountRepository.getByIds(ids)

    override suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Account> =
        accountRepository.getByCompany(companyId, offset, limit)

    override suspend fun create(input: AccountInput, principalId: UUID?): Account = transaction {
        val account = accountRepository.add(
            Account(companyId = input.companyId, type = input.type, extras = input.extras ?: EmptyAccountExtras),
        )
        input.customerIds.forEach { accountCustomerRepository.add(account.id, it) }
        auditService.record(
            entityType = "account",
            entityId = account.id,
            action = "created",
            serializer = Account.serializer(),
            after = account,
            principalId = principalId,
        )
        account
    }

    override suspend fun edit(id: UUID, input: AccountInput, principalId: UUID?): Account = transaction {
        val existing = accountRepository.get(id) ?: error("account $id not found")
        // updateProfile sets type/extras only — never credit — so a concurrent add/spendCredit isn't clobbered.
        val updated = accountRepository.updateProfile(
            existing.copy(type = input.type, extras = input.extras ?: existing.extras),
        ) ?: error("account $id not found")
        input.customerIds.forEach { accountCustomerRepository.add(id, it) }
        auditService.record(
            entityType = "account",
            entityId = id,
            action = "updated",
            serializer = Account.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = accountRepository.get(id) ?: return@transaction false
        accountRepository.softDelete(id)
        auditService.record(
            entityType = "account",
            entityId = id,
            action = "deleted",
            serializer = Account.serializer(),
            before = existing,
            principalId = principalId,
        )
        true
    }

    override suspend fun addAddress(accountId: UUID, input: AccountAddressInput, principalId: UUID?): AccountAddress =
        transaction {
            val address = accountAddressRepository.add(
                AccountAddress(
                    accountId = accountId,
                    type = input.type,
                    preferred = input.preferred,
                    address1 = input.address1,
                    address2 = input.address2,
                    city = input.city,
                    state = input.state,
                    country = input.country,
                    zip = input.zip,
                    phone = input.phone,
                    note = input.note,
                ),
            )
            auditService.record(
                entityType = "account_address",
                entityId = address.id,
                action = "created",
                serializer = AccountAddress.serializer(),
                after = address,
                principalId = principalId,
            )
            address
        }

    override suspend fun getAddresses(accountId: UUID): List<AccountAddress> =
        accountAddressRepository.getByAccount(accountId)

    override suspend fun addCredit(accountId: UUID, amount: Money, note: String?, principalId: UUID?): Account =
        adjustCredit(accountId, amount, "credit_added", note, principalId)

    override suspend fun spendCredit(accountId: UUID, amount: Money, note: String?, principalId: UUID?): Account =
        transaction {
            require(amount.isPositive) { "spend amount must be positive" }
            val account = accountRepository.getForUpdate(accountId) ?: error("account $accountId not found")
            check(account.credit >= amount) { "insufficient credit on account $accountId" }
            val updated = accountRepository.update(account.copy(credit = account.credit - amount))
                ?: error("account $accountId not found")
            auditService.record(
                entityType = "account",
                entityId = accountId,
                action = "credit_spent",
                serializer = Account.serializer(),
                before = account,
                after = updated,
                principalId = principalId,
                details = creditDetails(amount, note),
            )
            updated
        }

    override suspend fun getCustomers(accountId: UUID): List<Customer> = customerRepository.getByAccount(accountId)

    private suspend fun adjustCredit(
        accountId: UUID,
        amount: Money,
        action: String,
        note: String?,
        principalId: UUID?,
    ): Account = transaction {
        require(amount.isPositive) { "credit amount must be positive" }
        val account = accountRepository.getForUpdate(accountId) ?: error("account $accountId not found")
        val updated = accountRepository.update(account.copy(credit = account.credit + amount))
            ?: error("account $accountId not found")
        auditService.record(
            entityType = "account",
            entityId = accountId,
            action = action,
            serializer = Account.serializer(),
            before = account,
            after = updated,
            principalId = principalId,
            details = creditDetails(amount, note),
        )
        updated
    }

    private fun creditDetails(amount: Money, note: String?) = buildJsonObject {
        put("amount", amount.toString())
        note?.let { put("note", it) }
    }
}
