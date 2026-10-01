package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.EmptyCustomerExtras
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.CustomerService
import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Customer field wiring: scalar fields read the source; `company`/`profile`/`defaultAccount`/
 * `accounts` resolve through their services.
 */
@OptIn(ExperimentalUuidApi::class)
class CustomerControllerTest {

    private val companyService = mockk<CompanyService>()
    private val customerService = mockk<CustomerService>()
    private val accountService = mockk<AccountService>()
    private val profileService = mockk<ProfileService>()
    private val controller = CustomerController(companyService, customerService, accountService, profileService)

    private val companyId = UUID.random()
    private val profileId = UUID.random()
    private val defaultAccountId = UUID.random()
    private val customer = Customer(
        id = UUID.random(),
        companyId = companyId,
        profileId = profileId,
        defaultAccountId = defaultAccountId,
    )

    @Test
    fun `scalar fields resolve from the source customer`() {
        assertEquals(customer.id, controller.id(customer))
        assertEquals(EmptyCustomerExtras, controller.extras(customer))
        assertEquals(customer.created, controller.created(customer))
        assertEquals(customer.modified, controller.modified(customer))
    }

    @Test
    fun `company resolves via the company service`() = runTest {
        val company = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyService.get(companyId) } returns company
        assertEquals(company, controller.company(customer))
    }

    @Test
    fun `company throws when the company is missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(customer) }
    }

    @Test
    fun `profile resolves via the profile service`() = runTest {
        val profile = mockk<Profile>()
        coEvery { profileService.getById(profileId) } returns profile
        assertEquals(profile, controller.profile(customer))
    }

    @Test
    fun `defaultAccount resolves via the account service`() = runTest {
        val account = Account(id = defaultAccountId, companyId = companyId, type = AccountType.CONSUMER)
        coEvery { accountService.get(defaultAccountId) } returns account
        assertEquals(account, controller.defaultAccount(customer))
    }

    @Test
    fun `defaultAccount is null when the customer has none`() = runTest {
        assertNull(controller.defaultAccount(customer.copy(defaultAccountId = null)))
    }

    @Test
    fun `accounts resolves via the customer service`() = runTest {
        val account = Account(id = defaultAccountId, companyId = companyId, type = AccountType.CONSUMER)
        coEvery { customerService.getAccounts(customer.id) } returns listOf(account)
        assertEquals(listOf(account), controller.accounts(customer))
    }
}
