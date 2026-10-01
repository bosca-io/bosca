package bosca.ecommerce.service

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerInput
import bosca.ecommerce.model.EmptyCustomerExtras
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**customer orchestration — create, profile resolution, extras edit, default-account. */
@OptIn(ExperimentalUuidApi::class)
class CustomerServiceImplTest {

    private val customerRepository = mockk<CustomerRepository>(relaxUnitFun = true)
    private val accountRepository = mockk<AccountRepository>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: CustomerServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = CustomerServiceImpl(customerRepository, accountRepository, auditService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    @Test
    fun `create links the profile and audits`() = runTest {
        val companyId = UUID.random()
        val profileId = UUID.random()
        val customerId = UUID.random()
        val customer = Customer(id = customerId, companyId = companyId, profileId = profileId)
        coEvery { customerRepository.add(any()) } returns customer

        val result = service.create(CustomerInput(companyId = companyId), profileId, principalId = null)

        assertEquals(customer, result)
        coVerify(exactly = 1) { customerRepository.add(match { it.companyId == companyId && it.profileId == profileId }) }
        coVerify(exactly = 1) {
            auditService.record<Customer>(eq("customer"), eq(customerId), eq("created"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `getByProfile resolves a principal's customer`() = runTest {
        val companyId = UUID.random()
        val profileId = UUID.random()
        val customer = Customer(id = UUID.random(), companyId = companyId, profileId = profileId)
        coEvery { customerRepository.getByProfile(companyId, profileId) } returns customer

        assertEquals(customer, service.getByProfile(companyId, profileId))
    }

    @Test
    fun `editExtras updates and audits`() = runTest {
        val id = UUID.random()
        val existing = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())
        val newExtras = EmptyCustomerExtras
        coEvery { customerRepository.get(id) } returns existing
        val updated = slot<Customer>()
        coEvery { customerRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.editExtras(id, newExtras, principalId = null)

        assertEquals(newExtras, result.extras)
        coVerify(exactly = 1) {
            auditService.record<Customer>(eq("customer"), eq(id), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `setDefaultAccount updates and audits`() = runTest {
        val id = UUID.random()
        val accountId = UUID.random()
        val existing = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())
        coEvery { customerRepository.get(id) } returns existing
        val updated = slot<Customer>()
        coEvery { customerRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.setDefaultAccount(id, accountId, principalId = null)

        assertEquals(accountId, result.defaultAccountId)
        coVerify(exactly = 1) {
            auditService.record<Customer>(eq("customer"), eq(id), eq("default_account_changed"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val customer = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())
        coEvery { customerRepository.get(id) } returns customer

        assertEquals(customer, service.get(id))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        val customers = ids.map { Customer(id = it, companyId = UUID.random(), profileId = UUID.random()) }
        coEvery { customerRepository.getByIds(ids) } returns customers

        assertEquals(customers, service.getByIds(ids))
        coVerify(exactly = 1) { customerRepository.getByIds(ids) }
    }

    @Test
    fun `get returns null when the customer is missing`() = runTest {
        val id = UUID.random()
        coEvery { customerRepository.get(id) } returns null

        assertNull(service.get(id))
    }

    @Test
    fun `getByProfile returns null when no customer matches`() = runTest {
        val companyId = UUID.random()
        val profileId = UUID.random()
        coEvery { customerRepository.getByProfile(companyId, profileId) } returns null

        assertNull(service.getByProfile(companyId, profileId))
    }

    @Test
    fun `getByCompany delegates with paging`() = runTest {
        val companyId = UUID.random()
        val customers = listOf(Customer(id = UUID.random(), companyId = companyId, profileId = UUID.random()))
        coEvery { customerRepository.getByCompany(companyId, 4, 8) } returns customers

        assertEquals(customers, service.getByCompany(companyId, 4, 8))
        coVerify(exactly = 1) { customerRepository.getByCompany(companyId, 4, 8) }
    }

    @Test
    fun `create defaults to empty extras when none supplied`() = runTest {
        val companyId = UUID.random()
        val profileId = UUID.random()
        val saved = slot<Customer>()
        coEvery { customerRepository.add(capture(saved)) } answers { saved.captured.copy(id = UUID.random()) }

        service.create(CustomerInput(companyId = companyId, extras = null), profileId, principalId = null)

        assertEquals(EmptyCustomerExtras, saved.captured.extras)
    }

    @Test
    fun `create uses the supplied extras when present`() = runTest {
        val companyId = UUID.random()
        val profileId = UUID.random()
        val saved = slot<Customer>()
        coEvery { customerRepository.add(capture(saved)) } answers { saved.captured.copy(id = UUID.random()) }

        service.create(CustomerInput(companyId = companyId, extras = EmptyCustomerExtras), profileId, principalId = null)

        assertEquals(EmptyCustomerExtras, saved.captured.extras)
    }

    @Test
    fun `editExtras errors when the customer is missing`() = runTest {
        val id = UUID.random()
        coEvery { customerRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> {
            service.editExtras(id, EmptyCustomerExtras, principalId = null)
        }
        coVerify(exactly = 0) { customerRepository.update(any()) }
    }

    @Test
    fun `editExtras errors when the update returns null`() = runTest {
        val id = UUID.random()
        val existing = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())
        coEvery { customerRepository.get(id) } returns existing
        coEvery { customerRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.editExtras(id, EmptyCustomerExtras, principalId = null)
        }
        coVerify(exactly = 0) {
            auditService.record<Customer>(eq("customer"), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `setDefaultAccount errors when the customer is missing`() = runTest {
        val id = UUID.random()
        coEvery { customerRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> {
            service.setDefaultAccount(id, UUID.random(), principalId = null)
        }
        coVerify(exactly = 0) { customerRepository.update(any()) }
    }

    @Test
    fun `setDefaultAccount errors when the update returns null`() = runTest {
        val id = UUID.random()
        val existing = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())
        coEvery { customerRepository.get(id) } returns existing
        coEvery { customerRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.setDefaultAccount(id, UUID.random(), principalId = null)
        }
        coVerify(exactly = 0) {
            auditService.record<Customer>(eq("customer"), any(), eq("default_account_changed"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `delete soft-deletes and audits when the customer exists`() = runTest {
        val id = UUID.random()
        val actor = UUID.random()
        val existing = Customer(id = id, companyId = UUID.random(), profileId = UUID.random())
        coEvery { customerRepository.get(id) } returns existing

        assertTrue(service.delete(id, actor))

        coVerify(exactly = 1) { customerRepository.softDelete(id) }
        coVerify(exactly = 1) {
            auditService.record<Customer>(eq("customer"), eq(id), eq("deleted"), any(), any(), any(), eq(actor), any(), any(), any())
        }
    }

    @Test
    fun `delete returns false and does nothing when the customer is missing`() = runTest {
        val id = UUID.random()
        coEvery { customerRepository.get(id) } returns null

        assertFalse(service.delete(id, principalId = null))

        coVerify(exactly = 0) { customerRepository.softDelete(any()) }
        coVerify(exactly = 0) {
            auditService.record<Customer>(eq("customer"), any(), eq("deleted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `getAccounts delegates to the account repository`() = runTest {
        val customerId = UUID.random()
        val accounts = listOf(Account(id = UUID.random(), companyId = UUID.random(), type = AccountType.CONSUMER))
        coEvery { accountRepository.getByCustomer(customerId) } returns accounts

        assertEquals(accounts, service.getAccounts(customerId))
    }
}
