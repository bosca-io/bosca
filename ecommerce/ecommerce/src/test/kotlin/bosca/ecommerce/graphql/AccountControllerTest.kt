package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.EmptyAccountExtras
import bosca.ecommerce.model.Money
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CompanyService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Account field wiring: scalar fields read the source; `company` resolves via the company service;
 * `customers`/`addresses` page via the account service.
 */
@OptIn(ExperimentalUuidApi::class)
class AccountControllerTest {

    private val companyService = mockk<CompanyService>()
    private val accountService = mockk<AccountService>()
    private val controller = AccountController(companyService, accountService)

    private val companyId = UUID.random()
    private val account = Account(
        id = UUID.random(),
        companyId = companyId,
        type = AccountType.BUSINESS,
        credit = Money.of("25.00"),
    )

    @Test
    fun `scalar fields resolve from the source account`() {
        assertEquals(account.id, controller.id(account))
        assertEquals(AccountType.BUSINESS, controller.type(account))
        assertEquals(Money.of("25.00"), controller.credit(account))
        assertEquals(EmptyAccountExtras, controller.extras(account))
        assertEquals(account.created, controller.created(account))
        assertEquals(account.modified, controller.modified(account))
    }

    @Test
    fun `company resolves via the company service`() = runTest {
        val company = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyService.get(companyId) } returns company
        assertEquals(company, controller.company(account))
    }

    @Test
    fun `company throws when the company is missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(account) }
    }

    @Test
    fun `customers resolves via the account service`() = runTest {
        val customer = Customer(id = UUID.random(), companyId = companyId, profileId = UUID.random())
        coEvery { accountService.getCustomers(account.id) } returns listOf(customer)
        assertEquals(listOf(customer), controller.customers(account))
    }

    @Test
    fun `addresses resolves via the account service`() = runTest {
        val address = AccountAddress(
            id = UUID.random(), accountId = account.id, type = AddressType.SHIPPING,
            address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000", phone = "555",
        )
        coEvery { accountService.getAddresses(account.id) } returns listOf(address)
        assertEquals(listOf(address), controller.addresses(account))
    }
}
