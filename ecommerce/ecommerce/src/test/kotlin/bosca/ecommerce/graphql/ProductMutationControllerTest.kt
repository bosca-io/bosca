package bosca.ecommerce.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.content.security.MetadataPermissionEvaluator
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.ProductService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * Per-product mutations are gated on the backing content document's permissions (EDIT to edit,
 * MANAGE to delete) — not on a group. Each method authorizes against the metadata, then delegates.
 */
@OptIn(ExperimentalUuidApi::class)
class ProductMutationControllerTest {

    private val productService = mockk<ProductService>()
    private val metadataService = mockk<MetadataService>()
    private val metadataPermissions = mockk<MetadataPermissionEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ProductMutationController(productService, metadataService, metadataPermissions)

    private val productId = UUID.random()
    private val companyId = UUID.random()
    private val manufacturerId = UUID.random()
    private val metadataId = UUID.random()
    private val metadata = mockk<Metadata>()

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
        metadataId = metadataId, metadataVersion = 2, type = ProductType.PHYSICAL,
    )

    @Test
    fun `edit authorizes EDIT against the backing document then updates`() = runTest {
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 2) } returns metadata
        coEvery { productService.edit(productId, input(), null) } returns product()

        val result = controller.edit(auth, ProductMutation(productId), input())

        assertEquals(productId, result.id)
        coVerify(exactly = 1) { metadataPermissions.verifyAllowed(auth, metadata, PermissionAction.EDIT) }
        coVerify(exactly = 1) { productService.edit(productId, input(), null) }
    }

    @Test
    fun `delete authorizes MANAGE against the backing document then deletes`() = runTest {
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 2) } returns metadata
        coEvery { productService.delete(productId, null) } returns true

        assertEquals(true, controller.delete(auth, ProductMutation(productId)))

        coVerify(exactly = 1) { metadataPermissions.verifyAllowed(auth, metadata, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { productService.delete(productId, null) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 2) } returns metadata
        coEvery { productService.edit(productId, input(), principalId) } returns product()

        controller.edit(auth, ProductMutation(productId), input())

        coVerify(exactly = 1) { productService.edit(productId, input(), principalId) }
    }

    @Test
    fun `delete forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 2) } returns metadata
        coEvery { productService.delete(productId, principalId) } returns true

        controller.delete(auth, ProductMutation(productId))

        coVerify(exactly = 1) { productService.delete(productId, principalId) }
    }

    @Test
    fun `edit fails when the product is missing`() = runTest {
        coEvery { productService.get(productId) } returns null
        assertFailsWith<IllegalArgumentException> {
            controller.edit(auth, ProductMutation(productId), input())
        }
        coVerify(exactly = 0) { productService.edit(any(), any(), any()) }
    }

    @Test
    fun `edit fails when the backing document is missing`() = runTest {
        coEvery { productService.get(productId) } returns product()
        coEvery { metadataService.getById(metadataId, 2) } returns null
        assertFailsWith<IllegalArgumentException> {
            controller.edit(auth, ProductMutation(productId), input())
        }
        coVerify(exactly = 0) { productService.edit(any(), any(), any()) }
    }
}
