package bosca.ecommerce.graphql

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.service.CompanyService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Manufacturer field wiring: scalar fields read the source; `company` resolves via the company service. */
@OptIn(ExperimentalUuidApi::class)
class ManufacturerControllerTest {

    private val companyService = mockk<CompanyService>()
    private val controller = ManufacturerController(companyService)

    private val companyId = UUID.random()
    private val manufacturer = Manufacturer(
        id = UUID.random(), companyId = companyId, name = "Acme", extras = EmptyManufacturerExtras,
    )

    @Test
    fun `scalar fields resolve from the source manufacturer`() {
        assertEquals(manufacturer.id, controller.id(manufacturer))
        assertEquals("Acme", controller.name(manufacturer))
        assertEquals(EmptyManufacturerExtras, controller.extras(manufacturer))
        assertEquals(manufacturer.created, controller.created(manufacturer))
        assertEquals(manufacturer.modified, controller.modified(manufacturer))
    }

    @Test
    fun `company resolves via the company service`() = runTest {
        val company = Company(id = companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { companyService.get(companyId) } returns company

        assertEquals(companyId, controller.company(manufacturer).id)
    }

    @Test
    fun `company throws when the company is missing`() = runTest {
        coEvery { companyService.get(companyId) } returns null
        assertFailsWith<IllegalStateException> { controller.company(manufacturer) }
    }
}
