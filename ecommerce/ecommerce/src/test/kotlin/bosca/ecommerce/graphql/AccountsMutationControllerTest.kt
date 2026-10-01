package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountInput
import bosca.ecommerce.model.AccountType
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

/** Account creates + the per-account accessor under `EcomMutation.accounts`. Admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class AccountsMutationControllerTest {

    private val accountService = mockk<AccountService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = AccountsMutationController(accountService, groups)
    private val companyId = UUID.random()
    private val accountId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = AccountInput(companyId = companyId, type = AccountType.BUSINESS)

    private fun account() = Account(id = accountId, companyId = companyId, type = AccountType.BUSINESS)

    @Test
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { accountService.create(input(), null) } returns account()

        val result = controller.add(auth, input())

        assertEquals(accountId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { accountService.create(input(), null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { accountService.create(input(), principalId) } returns account()

        controller.add(auth, input())

        coVerify(exactly = 1) { accountService.create(input(), principalId) }
    }

    @Test
    fun `account accessor returns the id-scoped mutation namespace`() {
        assertEquals(accountId, controller.account(accountId).id)
    }
}
