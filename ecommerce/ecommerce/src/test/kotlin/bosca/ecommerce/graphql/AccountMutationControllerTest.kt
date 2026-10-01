package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountAddress
import bosca.ecommerce.model.AccountAddressInput
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Money
import bosca.ecommerce.service.AccountService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Mutations scoped to one account (the id carried by [AccountMutation]). Admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class AccountMutationControllerTest {

    private val accountService = mockk<AccountService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = AccountMutationController(accountService, groups)
    private val companyId = UUID.random()
    private val accountId = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun authenticated() {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
    }

    private fun account() = Account(id = accountId, companyId = companyId, type = AccountType.BUSINESS)

    @Test
    fun `edit gates on admin then edits via the service`() = runTest {
        val input = AccountInput(companyId = companyId, type = AccountType.CONSUMER)
        coEvery { accountService.edit(accountId, input, null) } returns account()

        val result = controller.edit(auth, AccountMutation(accountId), input)

        assertEquals(accountId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { accountService.edit(accountId, input, null) }
    }

    @Test
    fun `delete gates on admin then deletes via the service`() = runTest {
        coEvery { accountService.delete(accountId, null) } returns true

        assertEquals(true, controller.delete(auth, AccountMutation(accountId)))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { accountService.delete(accountId, null) }
    }

    @Test
    fun `addAddress gates on admin then persists via the service`() = runTest {
        val input = AccountAddressInput(
            type = AddressType.SHIPPING, address1 = "1 Main", city = "Town",
            state = "ST", country = "US", zip = "00000", phone = "555",
        )
        val address = AccountAddress(
            id = UUID.random(), accountId = accountId, type = AddressType.SHIPPING,
            address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000", phone = "555",
        )
        coEvery { accountService.addAddress(accountId, input, null) } returns address

        val result = controller.addAddress(auth, AccountMutation(accountId), input)

        assertEquals(address, result)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { accountService.addAddress(accountId, input, null) }
    }

    @Test
    fun `addCredit gates on admin then adds credit via the service`() = runTest {
        val amount = Money.of("15.00")
        coEvery { accountService.addCredit(accountId, amount, "promo", null) } returns account()

        val result = controller.addCredit(auth, AccountMutation(accountId), amount, "promo")

        assertEquals(accountId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { accountService.addCredit(accountId, amount, "promo", null) }
    }

    @Test
    fun `edit delegates with the authenticated principal id`() = runTest {
        authenticated()
        val input = AccountInput(companyId = companyId, type = AccountType.CONSUMER)
        coEvery { accountService.edit(accountId, input, principalId) } returns account()

        controller.edit(auth, AccountMutation(accountId), input)

        coVerify(exactly = 1) { accountService.edit(accountId, input, principalId) }
    }

    @Test
    fun `delete delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { accountService.delete(accountId, principalId) } returns true

        assertEquals(true, controller.delete(auth, AccountMutation(accountId)))

        coVerify(exactly = 1) { accountService.delete(accountId, principalId) }
    }

    @Test
    fun `addAddress delegates with the authenticated principal id`() = runTest {
        authenticated()
        val input = AccountAddressInput(
            type = AddressType.SHIPPING, address1 = "1 Main", city = "Town",
            state = "ST", country = "US", zip = "00000", phone = "555",
        )
        val address = AccountAddress(
            id = UUID.random(), accountId = accountId, type = AddressType.SHIPPING,
            address1 = "1 Main", city = "Town", state = "ST", country = "US", zip = "00000", phone = "555",
        )
        coEvery { accountService.addAddress(accountId, input, principalId) } returns address

        assertEquals(address, controller.addAddress(auth, AccountMutation(accountId), input))

        coVerify(exactly = 1) { accountService.addAddress(accountId, input, principalId) }
    }

    @Test
    fun `addCredit delegates with the authenticated principal id`() = runTest {
        authenticated()
        val amount = Money.of("15.00")
        coEvery { accountService.addCredit(accountId, amount, "promo", principalId) } returns account()

        controller.addCredit(auth, AccountMutation(accountId), amount, "promo")

        coVerify(exactly = 1) { accountService.addCredit(accountId, amount, "promo", principalId) }
    }
}
