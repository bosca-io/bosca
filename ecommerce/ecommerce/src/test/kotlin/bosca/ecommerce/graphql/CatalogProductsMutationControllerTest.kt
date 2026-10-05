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

/** Catalog-entry collection mutations: add is admin-gated; catalogProduct(id) opens the id namespace. */
@OptIn(ExperimentalUuidApi::class)
class CatalogProductsMutationControllerTest {

    private val catalogProductService = mockk<CatalogProductService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = CatalogProductsMutationController(catalogProductService, groups)

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
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { catalogProductService.create(input(), null) } returns entry()

        val result = controller.add(auth, input())

        assertEquals(entryId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { catalogProductService.create(input(), null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { catalogProductService.create(input(), principalId) } returns entry()

        controller.add(auth, input())

        coVerify(exactly = 1) { catalogProductService.create(input(), principalId) }
    }

    @Test
    fun `catalogProduct accessor returns the id-scoped mutation namespace`() {
        assertEquals(entryId, controller.catalogProduct(entryId).id)
    }
}
