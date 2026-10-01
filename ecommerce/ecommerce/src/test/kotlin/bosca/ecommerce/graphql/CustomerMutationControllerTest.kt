package bosca.ecommerce.graphql

import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.EmptyCustomerExtras
import bosca.ecommerce.service.CustomerService
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

/** Mutations scoped to one customer (the id carried by [CustomerMutation]). Admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class CustomerMutationControllerTest {

    private val customerService = mockk<CustomerService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = CustomerMutationController(customerService, groups)
    private val customerId = UUID.random()
    private val principalId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun authenticated() {
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
    }

    private fun customer() = Customer(id = customerId, companyId = UUID.random(), profileId = UUID.random())

    @Test
    fun `edit gates on admin then edits extras via the service`() = runTest {
        coEvery { customerService.editExtras(customerId, EmptyCustomerExtras, null) } returns customer()

        val result = controller.edit(auth, CustomerMutation(customerId), EmptyCustomerExtras)

        assertEquals(customerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { customerService.editExtras(customerId, EmptyCustomerExtras, null) }
    }

    @Test
    fun `setDefaultAccount gates on admin then delegates to the service`() = runTest {
        val accountId = UUID.random()
        coEvery { customerService.setDefaultAccount(customerId, accountId, null) } returns customer()

        val result = controller.setDefaultAccount(auth, CustomerMutation(customerId), accountId)

        assertEquals(customerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { customerService.setDefaultAccount(customerId, accountId, null) }
    }

    @Test
    fun `delete gates on admin then deletes via the service`() = runTest {
        coEvery { customerService.delete(customerId, null) } returns true

        assertEquals(true, controller.delete(auth, CustomerMutation(customerId)))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { customerService.delete(customerId, null) }
    }

    @Test
    fun `edit delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { customerService.editExtras(customerId, EmptyCustomerExtras, principalId) } returns customer()

        controller.edit(auth, CustomerMutation(customerId), EmptyCustomerExtras)

        coVerify(exactly = 1) { customerService.editExtras(customerId, EmptyCustomerExtras, principalId) }
    }

    @Test
    fun `setDefaultAccount delegates with the authenticated principal id`() = runTest {
        authenticated()
        val accountId = UUID.random()
        coEvery { customerService.setDefaultAccount(customerId, accountId, principalId) } returns customer()

        controller.setDefaultAccount(auth, CustomerMutation(customerId), accountId)

        coVerify(exactly = 1) { customerService.setDefaultAccount(customerId, accountId, principalId) }
    }

    @Test
    fun `delete delegates with the authenticated principal id`() = runTest {
        authenticated()
        coEvery { customerService.delete(customerId, principalId) } returns true

        assertEquals(true, controller.delete(auth, CustomerMutation(customerId)))

        coVerify(exactly = 1) { customerService.delete(customerId, principalId) }
    }
}
