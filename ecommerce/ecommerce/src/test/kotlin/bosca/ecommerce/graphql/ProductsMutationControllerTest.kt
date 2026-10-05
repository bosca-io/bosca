package bosca.ecommerce.graphql

import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.ProductService
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

/** Products collection mutations: add is admin-gated; product(id) opens the id namespace. */
@OptIn(ExperimentalUuidApi::class)
class ProductsMutationControllerTest {

    private val productService = mockk<ProductService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ProductsMutationController(productService, groups)

    private val companyId = UUID.random()
    private val manufacturerId = UUID.random()
    private val productId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = ProductInput(
        companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU1",
        type = ProductType.PHYSICAL, title = "Widget",
    )

    private fun product() = Product(
        id = productId, companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU1",
        metadataId = UUID.random(), type = ProductType.PHYSICAL,
    )

    @Test
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { productService.create(input(), null) } returns product()

        val result = controller.add(auth, input())

        assertEquals(productId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { productService.create(input(), null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { productService.create(input(), principalId) } returns product()

        controller.add(auth, input())

        coVerify(exactly = 1) { productService.create(input(), principalId) }
    }

    @Test
    fun `product accessor returns the id-scoped mutation namespace`() {
        assertEquals(productId, controller.product(productId).id)
    }
}
