package bosca.ecommerce.graphql

import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerInput
import bosca.ecommerce.service.ManufacturerService
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

/** Manufacturers collection mutations: add is admin-gated; manufacturer(id) opens the id namespace. */
@OptIn(ExperimentalUuidApi::class)
class ManufacturersMutationControllerTest {

    private val manufacturerService = mockk<ManufacturerService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ManufacturersMutationController(manufacturerService, groups)

    private val manufacturerId = UUID.random()
    private val companyId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = ManufacturerInput(companyId = companyId, name = "Acme", extras = null)

    private fun manufacturer() = Manufacturer(
        id = manufacturerId, companyId = companyId, name = "Acme", extras = EmptyManufacturerExtras,
    )

    @Test
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { manufacturerService.create(input(), null) } returns manufacturer()

        val result = controller.add(auth, input())

        assertEquals(manufacturerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { manufacturerService.create(input(), null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { manufacturerService.create(input(), principalId) } returns manufacturer()

        controller.add(auth, input())

        coVerify(exactly = 1) { manufacturerService.create(input(), principalId) }
    }

    @Test
    fun `manufacturer accessor returns the id-scoped mutation namespace`() {
        assertEquals(manufacturerId, controller.manufacturer(manufacturerId).id)
    }
}
