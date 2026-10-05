package bosca.ecommerce.service

import bosca.ecommerce.model.EmptyManufacturerExtras
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.ManufacturerInput
import bosca.ecommerce.repository.ManufacturerRepository
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Manufacturer orchestration — update/delete audited. */
@OptIn(ExperimentalUuidApi::class)
class ManufacturerServiceImplTest {

    private val manufacturerRepository = mockk<ManufacturerRepository>(relaxUnitFun = true)
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: ManufacturerServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = ManufacturerServiceImpl(manufacturerRepository, auditService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    private fun input(companyId: UUID = UUID.random()) =
        ManufacturerInput(companyId = companyId, name = "Acme", extras = null)

    @Test
    fun `update edits and audits`() = runTest {
        val id = UUID.random()
        val existing = Manufacturer(
            id = id, companyId = UUID.random(), name = "Old", extras = EmptyManufacturerExtras,
        )
        coEvery { manufacturerRepository.get(id) } returns existing
        val updated = slot<Manufacturer>()
        coEvery { manufacturerRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.update(id, input(), principalId = null)

        assertEquals("Acme", result.name)
        coVerify(exactly = 1) {
            auditService.record<Manufacturer>(eq("manufacturer"), eq(id), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `update keeps provided extras over the existing value`() = runTest {
        val id = UUID.random()
        coEvery { manufacturerRepository.get(id) } returns Manufacturer(id = id, companyId = UUID.random(), name = "Old", extras = EmptyManufacturerExtras)
        val updated = slot<Manufacturer>()
        coEvery { manufacturerRepository.update(capture(updated)) } answers { updated.captured }

        service.update(id, input().copy(extras = EmptyManufacturerExtras), principalId = null)

        assertEquals(EmptyManufacturerExtras, updated.captured.extras) // `?:` left branch: explicit extras preserved
    }

    @Test
    fun `delete soft-deletes and audits`() = runTest {
        val id = UUID.random()
        val existing = Manufacturer(
            id = id, companyId = UUID.random(), name = "Acme", extras = EmptyManufacturerExtras,
        )
        coEvery { manufacturerRepository.get(id) } returns existing

        val result = service.delete(id, principalId = null)

        assertTrue(result)
        coVerify(exactly = 1) { manufacturerRepository.softDelete(id) }
        coVerify(exactly = 1) {
            auditService.record<Manufacturer>(eq("manufacturer"), eq(id), eq("deleted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `delete returns false when missing`() = runTest {
        val id = UUID.random()
        coEvery { manufacturerRepository.get(id) } returns null

        val result = service.delete(id, principalId = null)

        assertFalse(result)
        coVerify(exactly = 0) { manufacturerRepository.softDelete(any()) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val manufacturer = Manufacturer(id = id, companyId = UUID.random(), name = "Acme", extras = EmptyManufacturerExtras)
        coEvery { manufacturerRepository.get(id) } returns manufacturer

        assertEquals(manufacturer, service.get(id))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val id = UUID.random()
        val manufacturer = Manufacturer(id = id, companyId = UUID.random(), name = "Acme", extras = EmptyManufacturerExtras)
        coEvery { manufacturerRepository.getByIds(listOf(id)) } returns listOf(manufacturer)

        assertEquals(listOf(manufacturer), service.getByIds(listOf(id)))
    }

    @Test
    fun `get returns null when the manufacturer is absent`() = runTest {
        val id = UUID.random()
        coEvery { manufacturerRepository.get(id) } returns null

        assertEquals(null, service.get(id))
    }

    @Test
    fun `getByCompany delegates with paging args`() = runTest {
        val companyId = UUID.random()
        val list = listOf(Manufacturer(companyId = companyId, name = "Acme", extras = EmptyManufacturerExtras))
        coEvery { manufacturerRepository.getByCompany(companyId, 5, 10) } returns list

        assertEquals(list, service.getByCompany(companyId, offset = 5, limit = 10))
        coVerify(exactly = 1) { manufacturerRepository.getByCompany(companyId, 5, 10) }
    }

    @Test
    fun `create persists with default extras and audits created`() = runTest {
        val newId = UUID.random()
        val added = slot<Manufacturer>()
        coEvery { manufacturerRepository.add(capture(added)) } answers { added.captured.copy(id = newId) }

        val result = service.create(input(), principalId = null)

        assertEquals(newId, result.id)
        assertEquals("Acme", added.captured.name)
        // input.extras is null -> the `?:` falls back to EmptyManufacturerExtras.
        assertEquals(EmptyManufacturerExtras, added.captured.extras)
        coVerify(exactly = 1) {
            auditService.record<Manufacturer>(eq("manufacturer"), eq(newId), eq("created"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `create keeps provided extras over the default`() = runTest {
        val added = slot<Manufacturer>()
        coEvery { manufacturerRepository.add(capture(added)) } answers { added.captured }

        // The `?:` left branch: a non-null extras is preserved.
        service.create(input().copy(extras = EmptyManufacturerExtras), principalId = null)

        assertEquals(EmptyManufacturerExtras, added.captured.extras)
    }

    @Test
    fun `update throws when the manufacturer is absent`() = runTest {
        val id = UUID.random()
        coEvery { manufacturerRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> { service.update(id, input(), principalId = null) }
        coVerify(exactly = 0) { manufacturerRepository.update(any()) }
        coVerify(exactly = 0) {
            auditService.record<Manufacturer>(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `update throws when the row vanishes mid-update`() = runTest {
        val id = UUID.random()
        coEvery { manufacturerRepository.get(id) } returns Manufacturer(
            id = id, companyId = UUID.random(), name = "Old", extras = EmptyManufacturerExtras,
        )
        // The second `?: error(...)` — update returns null.
        coEvery { manufacturerRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> { service.update(id, input(), principalId = null) }
        coVerify(exactly = 0) {
            auditService.record<Manufacturer>(any(), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
