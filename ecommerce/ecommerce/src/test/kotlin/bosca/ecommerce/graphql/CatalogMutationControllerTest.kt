package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogInput
import bosca.ecommerce.service.CatalogService
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

/** Per-catalog mutations: edit gates on the ecom-admin group, then delegates to the service. */
@OptIn(ExperimentalUuidApi::class)
class CatalogMutationControllerTest {

    private val catalogService = mockk<CatalogService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = CatalogMutationController(catalogService, groups)

    private val catalogId = UUID.random()
    private val companyId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = CatalogInput(companyId = companyId, key = "default", name = "Default")

    @Test
    fun `edit gates on admin then updates via the service`() = runTest {
        val catalog = Catalog(id = catalogId, companyId = companyId, key = "default", name = "Default")
        coEvery { catalogService.edit(catalogId, input(), null) } returns catalog

        val result = controller.edit(auth, CatalogMutation(catalogId), input())

        assertEquals(catalogId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { catalogService.edit(catalogId, input(), null) }
    }

    @Test
    fun `edit forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val catalog = Catalog(id = catalogId, companyId = companyId, key = "default", name = "Default")
        coEvery { catalogService.edit(catalogId, input(), principalId) } returns catalog

        controller.edit(auth, CatalogMutation(catalogId), input())

        coVerify(exactly = 1) { catalogService.edit(catalogId, input(), principalId) }
    }
}
