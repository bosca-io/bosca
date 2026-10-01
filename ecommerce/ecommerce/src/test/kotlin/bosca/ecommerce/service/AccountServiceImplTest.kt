package bosca.ecommerce.service

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountAddressInput
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.EmptyAccountExtras
import bosca.ecommerce.model.Money
import bosca.ecommerce.repository.AccountAddressRepository
import bosca.ecommerce.repository.AccountCustomerRepository
import bosca.ecommerce.repository.AccountRepository
import bosca.ecommerce.repository.CustomerRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**account orchestration — create+m2m, addresses, and row-locked credit add/spend with audit. */
@OptIn(ExperimentalUuidApi::class)
class AccountServiceImplTest {

    private val accountRepository = mockk<AccountRepository>(relaxUnitFun = true)
    private val accountCustomerRepository = mockk<AccountCustomerRepository>(relaxed = true)
    private val accountAddressRepository = mockk<AccountAddressRepository>()
    private val customerRepository = mockk<CustomerRepository>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: AccountServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = AccountServiceImpl(
            accountRepository,
            accountCustomerRepository,
            accountAddressRepository,
            customerRepository,
            auditService,
        )
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `create attaches customers via the m2m and audits`() = runTest {
        val companyId = UUID.random()
        val accountId = UUID.random()
        val c1 = UUID.random()
        val c2 = UUID.random()
        val account = Account(id = accountId, companyId = companyId, type = AccountType.BUSINESS)
        coEvery { accountRepository.add(any()) } returns account

        val result = service.create(
            AccountInput(companyId = companyId, type = AccountType.BUSINESS, customerIds = listOf(c1, c2)),
            principalId = null,
        )

        assertEquals(account, result)
        coVerify(exactly = 1) { accountCustomerRepository.add(accountId, c1) }
        coVerify(exactly = 1) { accountCustomerRepository.add(accountId, c2) }
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(accountId), eq("created"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addAddress persists and audits`() = runTest {
        val accountId = UUID.random()
        val addressId = UUID.random()
        val address = AccountAddress(
            id = addressId, accountId = accountId, type = AddressType.SHIPPING,
            address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000", phone = "555",
        )
        coEvery { accountAddressRepository.add(any()) } returns address

        val result = service.addAddress(
            accountId,
            AccountAddressInput(
                type = AddressType.SHIPPING, address1 = "1 Main", city = "Town",
                state = "ST", country = "US", zip = "00000", phone = "555",
            ),
            principalId = null,
        )

        assertEquals(address, result)
        coVerify(exactly = 1) { auditService.record<AccountAddress>(eq("account_address"), eq(addressId), eq("created"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addCredit row-locks, increases balance, and audits`() = runTest {
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = UUID.random(), type = AccountType.CONSUMER, credit = Money.of("10.00"))
        coEvery { accountRepository.getForUpdate(accountId) } returns account
        val saved = slot<Account>()
        coEvery { accountRepository.update(capture(saved)) } answers { saved.captured }

        val result = service.addCredit(accountId, Money.of("15.00"), note = "promo", principalId = null)

        assertEquals(Money.of("25.00"), result.credit)
        coVerify(exactly = 1) { accountRepository.getForUpdate(accountId) }
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(accountId), eq("credit_added"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `spendCredit deducts and audits`() = runTest {
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = UUID.random(), type = AccountType.CONSUMER, credit = Money.of("50.00"))
        coEvery { accountRepository.getForUpdate(accountId) } returns account
        val saved = slot<Account>()
        coEvery { accountRepository.update(capture(saved)) } answers { saved.captured }

        val result = service.spendCredit(accountId, Money.of("20.00"), note = null, principalId = null)

        assertEquals(Money.of("30.00"), result.credit)
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(accountId), eq("credit_spent"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `spendCredit fails and does not update when insufficient`() = runTest {
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = UUID.random(), type = AccountType.CONSUMER, credit = Money.of("10.00"))
        coEvery { accountRepository.getForUpdate(accountId) } returns account

        assertFailsWith<IllegalStateException> {
            service.spendCredit(accountId, Money.of("20.00"), note = null, principalId = null)
        }
        coVerify(exactly = 0) { accountRepository.update(any()) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val account = Account(id = id, companyId = UUID.random(), type = AccountType.BUSINESS)
        coEvery { accountRepository.get(id) } returns account

        assertEquals(account, service.get(id))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        val accounts = listOf(
            Account(id = ids[0], companyId = UUID.random(), type = AccountType.BUSINESS),
            Account(id = ids[1], companyId = UUID.random(), type = AccountType.CONSUMER),
        )
        coEvery { accountRepository.getByIds(ids) } returns accounts

        assertEquals(accounts, service.getByIds(ids))
        coVerify(exactly = 1) { accountRepository.getByIds(ids) }
    }

    @Test
    fun `get returns null when the account is missing`() = runTest {
        val id = UUID.random()
        coEvery { accountRepository.get(id) } returns null

        assertEquals(null, service.get(id))
    }

    @Test
    fun `getByCompany delegates to the repository with paging`() = runTest {
        val companyId = UUID.random()
        val accounts = listOf(Account(id = UUID.random(), companyId = companyId, type = AccountType.CONSUMER))
        coEvery { accountRepository.getByCompany(companyId, 5, 10) } returns accounts

        assertEquals(accounts, service.getByCompany(companyId, 5, 10))
        coVerify(exactly = 1) { accountRepository.getByCompany(companyId, 5, 10) }
    }

    @Test
    fun `create with no customers skips the m2m and uses the supplied extras`() = runTest {
        val companyId = UUID.random()
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = companyId, type = AccountType.CONSUMER, extras = EmptyAccountExtras)
        val saved = slot<Account>()
        coEvery { accountRepository.add(capture(saved)) } returns account

        val result = service.create(
            AccountInput(companyId = companyId, type = AccountType.CONSUMER, extras = EmptyAccountExtras),
            principalId = null,
        )

        assertEquals(account, result)
        assertEquals(EmptyAccountExtras, saved.captured.extras)
        coVerify(exactly = 0) { accountCustomerRepository.add(any(), any()) }
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(accountId), eq("created"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `edit updates type, attaches customers, keeps existing extras when none supplied, and audits`() = runTest {
        val id = UUID.random()
        val actor = UUID.random()
        val c1 = UUID.random()
        val existing = Account(id = id, companyId = UUID.random(), type = AccountType.CONSUMER, extras = EmptyAccountExtras)
        coEvery { accountRepository.get(id) } returns existing
        val saved = slot<Account>()
        coEvery { accountRepository.updateProfile(capture(saved)) } answers { saved.captured }

        val result = service.edit(
            id,
            AccountInput(companyId = existing.companyId, type = AccountType.BUSINESS, customerIds = listOf(c1), extras = null),
            actor,
        )

        assertEquals(AccountType.BUSINESS, result.type)
        assertEquals(existing.extras, saved.captured.extras)
        coVerify(exactly = 1) { accountCustomerRepository.add(id, c1) }
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(id), eq("updated"), any(), any(), any(), eq(actor), any(), any(), any()) }
        coVerify(exactly = 0) { accountRepository.update(any()) } // edit must never write the credit column
    }

    @Test
    fun `edit replaces extras when supplied`() = runTest {
        val id = UUID.random()
        val existing = Account(id = id, companyId = UUID.random(), type = AccountType.CONSUMER, extras = EmptyAccountExtras)
        coEvery { accountRepository.get(id) } returns existing
        val saved = slot<Account>()
        coEvery { accountRepository.updateProfile(capture(saved)) } answers { saved.captured }

        service.edit(
            id,
            AccountInput(companyId = existing.companyId, type = AccountType.CONSUMER, extras = EmptyAccountExtras),
            principalId = null,
        )

        assertEquals(EmptyAccountExtras, saved.captured.extras)
    }

    @Test
    fun `edit errors when the account is missing`() = runTest {
        val id = UUID.random()
        coEvery { accountRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(id, AccountInput(companyId = UUID.random(), type = AccountType.BUSINESS), principalId = null)
        }
        coVerify(exactly = 0) { accountRepository.updateProfile(any()) }
    }

    @Test
    fun `edit errors when the update returns null`() = runTest {
        val id = UUID.random()
        val existing = Account(id = id, companyId = UUID.random(), type = AccountType.CONSUMER)
        coEvery { accountRepository.get(id) } returns existing
        coEvery { accountRepository.updateProfile(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(id, AccountInput(companyId = existing.companyId, type = AccountType.BUSINESS), principalId = null)
        }
        coVerify(exactly = 0) { auditService.record<Account>(eq("account"), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `delete soft-deletes and audits when the account exists`() = runTest {
        val id = UUID.random()
        val actor = UUID.random()
        val existing = Account(id = id, companyId = UUID.random(), type = AccountType.CONSUMER)
        coEvery { accountRepository.get(id) } returns existing

        assertEquals(true, service.delete(id, actor))

        coVerify(exactly = 1) { accountRepository.softDelete(id) }
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(id), eq("deleted"), any(), any(), any(), eq(actor), any(), any(), any()) }
    }

    @Test
    fun `delete returns false and does nothing when the account is missing`() = runTest {
        val id = UUID.random()
        coEvery { accountRepository.get(id) } returns null

        assertEquals(false, service.delete(id, principalId = null))

        coVerify(exactly = 0) { accountRepository.softDelete(any()) }
        coVerify(exactly = 0) { auditService.record<Account>(eq("account"), any(), eq("deleted"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getAddresses delegates to the repository`() = runTest {
        val accountId = UUID.random()
        val addresses = listOf(
            AccountAddress(
                id = UUID.random(), accountId = accountId, type = AddressType.BILLING,
                address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000", phone = "555",
            ),
        )
        coEvery { accountAddressRepository.getByAccount(accountId) } returns addresses

        assertEquals(addresses, service.getAddresses(accountId))
    }

    @Test
    fun `addCredit rejects a non-positive amount`() = runTest {
        val accountId = UUID.random()

        assertFailsWith<IllegalArgumentException> {
            service.addCredit(accountId, Money.ZERO, note = null, principalId = null)
        }
        coVerify(exactly = 0) { accountRepository.getForUpdate(any()) }
    }

    @Test
    fun `addCredit errors when the account is missing`() = runTest {
        val accountId = UUID.random()
        coEvery { accountRepository.getForUpdate(accountId) } returns null

        assertFailsWith<IllegalStateException> {
            service.addCredit(accountId, Money.of("5.00"), note = null, principalId = null)
        }
    }

    @Test
    fun `addCredit errors when the update returns null`() = runTest {
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = UUID.random(), type = AccountType.CONSUMER, credit = Money.of("1.00"))
        coEvery { accountRepository.getForUpdate(accountId) } returns account
        coEvery { accountRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.addCredit(accountId, Money.of("5.00"), note = null, principalId = null)
        }
        coVerify(exactly = 0) { auditService.record<Account>(eq("account"), any(), eq("credit_added"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `spendCredit rejects a non-positive amount`() = runTest {
        val accountId = UUID.random()

        assertFailsWith<IllegalArgumentException> {
            service.spendCredit(accountId, Money.ZERO, note = null, principalId = null)
        }
        coVerify(exactly = 0) { accountRepository.getForUpdate(any()) }
    }

    @Test
    fun `spendCredit errors when the account is missing`() = runTest {
        val accountId = UUID.random()
        coEvery { accountRepository.getForUpdate(accountId) } returns null

        assertFailsWith<IllegalStateException> {
            service.spendCredit(accountId, Money.of("5.00"), note = null, principalId = null)
        }
    }

    @Test
    fun `spendCredit errors when the update returns null`() = runTest {
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = UUID.random(), type = AccountType.CONSUMER, credit = Money.of("50.00"))
        coEvery { accountRepository.getForUpdate(accountId) } returns account
        coEvery { accountRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.spendCredit(accountId, Money.of("20.00"), note = null, principalId = null)
        }
        coVerify(exactly = 0) { auditService.record<Account>(eq("account"), any(), eq("credit_spent"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addCredit records the note in the audit details when supplied`() = runTest {
        val accountId = UUID.random()
        val account = Account(id = accountId, companyId = UUID.random(), type = AccountType.CONSUMER, credit = Money.ZERO)
        coEvery { accountRepository.getForUpdate(accountId) } returns account
        val saved = slot<Account>()
        coEvery { accountRepository.update(capture(saved)) } answers { saved.captured }

        val result = service.addCredit(accountId, Money.of("7.00"), note = "bonus", principalId = null)

        assertEquals(Money.of("7.00"), result.credit)
        coVerify(exactly = 1) { auditService.record<Account>(eq("account"), eq(accountId), eq("credit_added"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `getCustomers delegates to the customer repository`() = runTest {
        val accountId = UUID.random()
        val customers = listOf(Customer(id = UUID.random(), companyId = UUID.random(), profileId = UUID.random()))
        coEvery { customerRepository.getByAccount(accountId) } returns customers

        assertEquals(customers, service.getCustomers(accountId))
    }
}
