package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.ShippingProvider
import bosca.ecommerce.service.CompanyService
import bosca.ecommerce.service.ProviderService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**
 * FulfillmentCenter field wiring: scalar fields read the source; `company` resolves through the company
 * service (erroring when missing); `shippingProvider` resolves through the provider service (nullable).
 */
@OptIn(ExperimentalUuidApi::class)
class FulfillmentCenterControllerTest {

    private val companyService = mockk<CompanyService>()
    private val providerService = mockk<ProviderService>()
    private val controller = FulfillmentCenterController(companyService, providerService)

    private val companyId = UUID.random()
    private val shippingProviderId = UUID.random()
    private val center = FulfillmentCenter(
        id = UUID.random(), companyId = companyId, name = "DC", connectorKey = "manual",
        shippingProviderId = shippingProviderId,
        address1 = "1 Main", address2 = "Suite 2", city = "Springfield", state = "CA", country = "US", zip = "94000",
    )

    @Test
    fun `scalar fields resolve from the source center`() {
        assertEquals(center.id, controller.id(center))
        assertEquals("DC", controller.name(center))
        assertEquals("manual", controller.connectorKey(center))
        assertEquals("1 Main", controller.address1(center))
        assertEquals("Suite 2", controller.address2(center))
        assertEquals("Springfield", controller.city(center))
        assertEquals("CA", controller.state(center))
        assertEquals("US", controller.country(center))
        assertEquals("94000", controller.zip(center))
    }

    @Test
    fun `company resolves through the service`() = runTest {
        val company = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyService.get(companyId) } returns company
        assertEquals(company, controller.company(center))
    }

    @Test
    fun `company errors when missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(center) }
    }

    @Test
    fun `shippingProvider resolves through the service`() = runTest {
        val provider = ShippingProvider(
            id = shippingProviderId, companyId = companyId, name = "USPS", key = "usps", providerKey = "fixed-rate",
        )
        coEvery { providerService.getShippingProvider(shippingProviderId) } returns provider
        assertEquals(provider, controller.shippingProvider(center))
    }

    @Test
    fun `shippingProvider returns null when absent`() = runTest {
        coEvery { providerService.getShippingProvider(shippingProviderId) } returns null
        assertNull(controller.shippingProvider(center))
    }
}
