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

/** Per-manufacturer mutations: each gates on the ecom-admin group, then delegates to the service. */
@OptIn(ExperimentalUuidApi::class)
class ManufacturerMutationControllerTest {

    private val manufacturerService = mockk<ManufacturerService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ManufacturerMutationController(manufacturerService, groups)

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
    fun `edit gates on admin then updates via the service`() = runTest {
        coEvery { manufacturerService.update(manufacturerId, input(), null) } returns manufacturer()

        val result = controller.edit(auth, ManufacturerMutation(manufacturerId), input())

        assertEquals(manufacturerId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { manufacturerService.update(manufacturerId, input(), null) }
    }

    @Test
    fun `delete gates on admin then deletes via the service`() = runTest {
        coEvery { manufacturerService.delete(manufacturerId, null) } returns true

        assertEquals(true, controller.delete(auth, ManufacturerMutation(manufacturerId)))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { manufacturerService.delete(manufacturerId, null) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { manufacturerService.update(manufacturerId, input(), principalId) } returns manufacturer()

        controller.edit(auth, ManufacturerMutation(manufacturerId), input())

        coVerify(exactly = 1) { manufacturerService.update(manufacturerId, input(), principalId) }
    }

    @Test
    fun `delete forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { manufacturerService.delete(manufacturerId, principalId) } returns true

        controller.delete(auth, ManufacturerMutation(manufacturerId))

        coVerify(exactly = 1) { manufacturerService.delete(manufacturerId, principalId) }
    }
}
