@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.cache.CacheManager
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogInput
import bosca.ecommerce.model.Company
import bosca.ecommerce.repository.CatalogRepository
import bosca.graphql.Batch
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
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**catalog orchestration — reads delegate; create/edit are audited; missing rows fail. */
@OptIn(ExperimentalUuidApi::class)
class CatalogServiceImplTest {

    private val catalogRepository = mockk<CatalogRepository>()
    private val companyService = mockk<CompanyService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: CatalogServiceImpl

    private val companyId = UUID.random()
    private val catalogId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // The service builds a batch-only ServiceCache at construction (which resolves a CacheManager from DI).
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        service = CatalogServiceImpl(catalogRepository, companyService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun input() = CatalogInput(companyId = companyId, key = "default", name = "Default")
    private fun catalog() = Catalog(id = catalogId, companyId = companyId, key = "default", name = "Default")
    private fun catalog(id: UUID, companyId: UUID = UUID.random()) = Catalog(id = id, companyId = companyId, key = "k", name = "n")
    private fun company(id: UUID) = Company(id = id, organizationId = UUID.random(), profileId = UUID.random())

    @Test
    fun `get getByKey getByIds and getByCompany delegate to the repository`() = runTest {
        coEvery { catalogRepository.get(catalogId) } returns catalog()
        coEvery { catalogRepository.getByKey(companyId, "default") } returns catalog()
        coEvery { catalogRepository.getByCompany(companyId) } returns listOf(catalog())
        coEvery { catalogRepository.getByIds(listOf(catalogId)) } returns listOf(catalog())

        assertEquals(catalogId, service.get(catalogId)?.id)
        assertEquals("default", service.getByKey(companyId, "default")?.key)
        assertEquals(1, service.getByCompany(companyId).size)
        assertEquals(1, service.getByIds(listOf(catalogId)).size)
    }

    @Test
    fun `create persists the catalog from the input and audits created`() = runTest {
        val added = slot<Catalog>()
        coEvery { catalogRepository.add(capture(added)) } answers { added.captured.copy(id = catalogId) }

        val result = service.create(input(), principalId = null)

        assertEquals(catalogId, result.id)
        assertEquals(companyId, added.captured.companyId)
        assertEquals("default", added.captured.key)
        coVerify(exactly = 1) {
            auditService.record<Catalog>(entityType = "catalog", entityId = catalogId, action = "created", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `edit updates the existing catalog and audits updated`() = runTest {
        coEvery { catalogRepository.get(catalogId) } returns catalog()
        val updated = slot<Catalog>()
        coEvery { catalogRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.edit(catalogId, input().copy(key = "k2", name = "Renamed"), principalId = null)

        assertEquals("Renamed", result.name)
        assertEquals("k2", updated.captured.key)
        coVerify(exactly = 1) {
            auditService.record<Catalog>(entityType = "catalog", entityId = catalogId, action = "updated", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = any(), details = any())
        }
    }

    @Test
    fun `edit throws when the catalog is absent`() = runTest {
        coEvery { catalogRepository.get(catalogId) } returns null
        assertFailsWith<IllegalStateException> { service.edit(catalogId, input(), principalId = null) }
        coVerify(exactly = 0) { catalogRepository.update(any()) }
    }

    @Test
    fun `edit throws when the update finds no row`() = runTest {
        coEvery { catalogRepository.get(catalogId) } returns catalog()
        coEvery { catalogRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.edit(catalogId, input(), principalId = null) }
    }

    @Test
    fun `edit rejects a changed currency (immutable price-book anchor)`() = runTest {
        coEvery { catalogRepository.get(catalogId) } returns catalog() // USD
        assertFailsWith<IllegalStateException> {
            service.edit(catalogId, input().copy(currency = "EUR"), principalId = null)
        }
        coVerify(exactly = 0) { catalogRepository.update(any()) } // rejected before any write
    }

    // --- the DataLoader batch resolver behind Catalog.company ---

    @Test
    fun `loadCompaniesForCatalogs batches with O(1) loads and skips catalogs whose company is missing`() = runTest {
        val c1 = catalog(UUID.random()); val c2 = catalog(UUID.random()); val c3 = catalog(UUID.random()) // c3's company is absent
        val co1 = company(c1.companyId); val co2 = company(c2.companyId)
        coEvery { catalogRepository.getByIds(any()) } returns listOf(c1, c2, c3)
        coEvery { companyService.getByIds(any()) } returns listOf(co1, co2) // no co3
        val batch = Batch<UUID, Company>(listOf(c1.id, c2.id, c3.id))

        service.loadCompaniesForCatalogs(listOf(c1.id, c2.id, c3.id), batch)

        assertEquals(co1, batch.getData(c1.id))
        assertEquals(co2, batch.getData(c2.id))
        assertNull(batch.getData(c3.id)) // missing company -> not set
        // O(1): one batched catalog load + one batched company load, not one per row.
        coVerify(exactly = 1) { catalogRepository.getByIds(any()) }
        coVerify(exactly = 1) { companyService.getByIds(any()) }
    }
}
