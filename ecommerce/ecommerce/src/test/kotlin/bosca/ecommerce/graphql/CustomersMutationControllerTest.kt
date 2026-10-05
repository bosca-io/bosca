package bosca.ecommerce.graphql

import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.CustomerInput
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
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Customer creates + the per-customer accessor under `EcomMutation.customers`. Admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class CustomersMutationControllerTest {

    private val customerService = mockk<CustomerService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = CustomersMutationController(customerService, groups)
    private val companyId = UUID.random()
    private val profileId = UUID.random()
    private val customerId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = CustomerInput(companyId = companyId, profileId = profileId)

    private fun customer() = Customer(id = customerId, companyId = companyId, profileId = profileId)

    @Test
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { customerService.create(input(), profileId, null) } returns customer()

        val result = controller.add(auth, input())

        assertEquals(customerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { customerService.create(input(), profileId, null) }
    }

    @Test
    fun `add fails when profileId is absent`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            controller.add(auth, CustomerInput(companyId = companyId, profileId = null))
        }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { customerService.create(input(), profileId, principalId) } returns customer()

        controller.add(auth, input())

        coVerify(exactly = 1) { customerService.create(input(), profileId, principalId) }
    }

    @Test
    fun `customer accessor returns the id-scoped mutation namespace`() {
        assertEquals(customerId, controller.customer(customerId).id)
    }
}
