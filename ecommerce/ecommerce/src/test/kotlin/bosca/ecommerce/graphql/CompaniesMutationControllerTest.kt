package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.CompanyInput
import bosca.ecommerce.service.CompanyService
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

/** Company creates + the per-company accessor under `EcomMutation.companies`. Admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class CompaniesMutationControllerTest {

    private val companyService = mockk<CompanyService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()

    private val controller = CompaniesMutationController(companyService, groups)
    private val companyId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = CompanyInput(name = "Acme")

    private fun company() = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())

    @Test
    fun `add gates on admin then creates via the service`() = runTest {
        coEvery { companyService.create(input(), null) } returns company()

        val result = controller.add(auth, input())

        assertEquals(companyId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { companyService.create(input(), null) }
    }

    @Test
    fun `add forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { companyService.create(input(), principalId) } returns company()

        controller.add(auth, input())

        coVerify(exactly = 1) { companyService.create(input(), principalId) }
    }

    @Test
    fun `company accessor returns the id-scoped mutation namespace`() {
        assertEquals(companyId, controller.company(companyId).id)
    }
}
