package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.service.CompanyService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** ShippingProvider field wiring: scalars passthrough; [company] resolves; [configuration] is admin-gated. */
@OptIn(ExperimentalUuidApi::class)
class ShippingProviderControllerTest {

    private val companyService = mockk<CompanyService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = ShippingProviderController(companyService, groups)

    private val companyId = UUID.random()
    private val config = KeyValueProviderConfiguration(mapOf("k" to "v"))
    private val provider = ShippingProvider(
        id = UUID.random(), companyId = companyId, name = "Ship", key = "ship", providerKey = "test",
        configuration = config,
    )

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    @Test
    fun `scalar fields resolve from the source provider`() {
        assertEquals(provider.id, controller.id(provider))
        assertEquals("Ship", controller.name(provider))
        assertEquals("ship", controller.key(provider))
        assertEquals("test", controller.providerKey(provider))
    }

    @Test
    fun `company resolves via the company service`() = runTest {
        coEvery { companyService.get(companyId) } returns
            Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        assertEquals(companyId, controller.company(provider).id)
    }

    @Test
    fun `company errors when missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(provider) }
    }

    @Test
    fun `configuration gates on admin then returns the configuration`() {
        assertEquals(config, controller.configuration(auth, provider))
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
    }

    @Test
    fun `configuration defaults to the empty configuration`() {
        val plain = provider.copy(configuration = EmptyProviderConfiguration)
        assertEquals(EmptyProviderConfiguration, controller.configuration(auth, plain))
    }
}
