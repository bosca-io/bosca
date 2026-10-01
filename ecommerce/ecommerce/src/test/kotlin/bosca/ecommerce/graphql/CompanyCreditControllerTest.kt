package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyCredit
import bosca.ecommerce.model.Money
import bosca.ecommerce.service.AccountService
import bosca.ecommerce.service.CompanyService
import bosca.serialization.OffsetDateTime
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
 * CompanyCredit field wiring: scalar fields read the source; `company`/`account` resolve through
 * their services.
 */
@OptIn(ExperimentalUuidApi::class)
class CompanyCreditControllerTest {

    private val companyService = mockk<CompanyService>()
    private val accountService = mockk<AccountService>()
    private val controller = CompanyCreditController(companyService, accountService)

    private val companyId = UUID.random()
    private val accountId = UUID.random()
    private val expires = OffsetDateTime.now().plusDays(30)
    private val credit = CompanyCredit(
        id = UUID.random(),
        companyId = companyId,
        accountId = accountId,
        number = "GC-1",
        description = "promo",
        balance = Money.of("40.00"),
        paid = Money.of("10.00"),
        expires = expires,
    )

    @Test
    fun `scalar fields resolve from the source credit`() {
        assertEquals(credit.id, controller.id(credit))
        assertEquals("GC-1", controller.number(credit))
        assertEquals("promo", controller.description(credit))
        assertEquals(Money.of("40.00"), controller.balance(credit))
        assertEquals(Money.of("10.00"), controller.paid(credit))
        assertEquals(expires, controller.expires(credit))
        assertEquals(credit.created, controller.created(credit))
    }

    @Test
    fun `company resolves via the company service`() = runTest {
        val company = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyService.get(companyId) } returns company
        assertEquals(company, controller.company(credit))
    }

    @Test
    fun `company throws when the company is missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(credit) }
    }

    @Test
    fun `account resolves via the account service`() = runTest {
        val account = Account(id = accountId, companyId = companyId, type = AccountType.BUSINESS)
        coEvery { accountService.get(accountId) } returns account
        assertEquals(account, controller.account(credit))
    }

    @Test
    fun `account is null when the credit is not account-restricted`() = runTest {
        assertNull(controller.account(credit.copy(accountId = null)))
    }
}
