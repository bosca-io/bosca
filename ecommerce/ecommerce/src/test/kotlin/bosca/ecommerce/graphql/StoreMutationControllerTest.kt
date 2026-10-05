package bosca.ecommerce.graphql

import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreInput
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.service.StoreService
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

/** Per-store mutation: gates on the ecom-admin group, then edits via the service with the principal. */
@OptIn(ExperimentalUuidApi::class)
class StoreMutationControllerTest {

    private val storeService = mockk<StoreService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = StoreMutationController(storeService, groups)

    private val storeId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = StoreInput(
        companyId = UUID.random(), identifier = "shop", name = "Shop", catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )

    private fun store() = Store(
        id = storeId, identifier = "shop", name = "Shop", companyId = UUID.random(), catalogId = UUID.random(),
        type = StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )

    @Test
    fun `edit gates on admin then edits via the service`() = runTest {
        coEvery { storeService.edit(storeId, any(), null) } returns store()

        assertEquals(storeId, controller.edit(auth, StoreMutation(storeId), input()).id)

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { storeService.edit(storeId, any(), null) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { storeService.edit(storeId, any(), principalId) } returns store()

        controller.edit(auth, StoreMutation(storeId), input())

        coVerify(exactly = 1) { storeService.edit(storeId, any(), principalId) }
    }
}
