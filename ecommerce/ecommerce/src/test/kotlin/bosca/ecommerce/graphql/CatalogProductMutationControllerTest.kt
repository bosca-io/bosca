package bosca.ecommerce.graphql

import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductInput
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.CatalogProductService
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

/** Per-catalog-entry mutations: each gates on the ecom-admin group, then delegates to the service. */
@OptIn(ExperimentalUuidApi::class)
class CatalogProductMutationControllerTest {

    private val catalogProductService = mockk<CatalogProductService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = CatalogProductMutationController(catalogProductService, groups)

    private val entryId = UUID.random()
    private val catalogId = UUID.random()
    private val productId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = CatalogProductInput(catalogId = catalogId, productId = productId, price = Money.of("9.99"))

    private fun entry() = CatalogProduct(
        id = entryId, catalogId = catalogId, productId = productId,
        type = ProductType.PHYSICAL, price = Money.of("9.99"),
    )

    @Test
    fun `edit gates on admin then updates via the service`() = runTest {
        coEvery { catalogProductService.edit(entryId, input(), null) } returns entry()

        val result = controller.edit(auth, CatalogProductMutation(entryId), input())

        assertEquals(entryId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { catalogProductService.edit(entryId, input(), null) }
    }

    @Test
    fun `delete gates on admin then deletes via the service`() = runTest {
        coEvery { catalogProductService.delete(entryId, null) } returns true

        assertEquals(true, controller.delete(auth, CatalogProductMutation(entryId)))

        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { catalogProductService.delete(entryId, null) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { catalogProductService.edit(entryId, input(), principalId) } returns entry()

        controller.edit(auth, CatalogProductMutation(entryId), input())

        coVerify(exactly = 1) { catalogProductService.edit(entryId, input(), principalId) }
    }

    @Test
    fun `delete forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { catalogProductService.delete(entryId, principalId) } returns true

        controller.delete(auth, CatalogProductMutation(entryId))

        coVerify(exactly = 1) { catalogProductService.delete(entryId, principalId) }
    }
}
