@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.cache.CacheManager
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductInput
import bosca.ecommerce.model.EmptyCatalogProductExtras
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.repository.CatalogProductRepository
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTime
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
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**catalog-entry orchestration — type derived from the product, and audited price changes. */
@OptIn(ExperimentalUuidApi::class)
class CatalogProductServiceImplTest {

    private val catalogProductRepository = mockk<CatalogProductRepository>(relaxUnitFun = true)
    private val productService = mockk<ProductService>()
    private val catalogService = mockk<CatalogService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: CatalogProductServiceImpl

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // The service builds batch-only ServiceCaches at construction (which resolve a CacheManager from DI).
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        service = CatalogProductServiceImpl(catalogProductRepository, productService, catalogService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `create derives the entry type from the product and audits`() = runTest {
        val catalogId = UUID.random()
        val productId = UUID.random()
        val entryId = UUID.random()
        coEvery { productService.get(productId) } returns Product(
            id = productId, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.SUBSCRIPTION,
        )
        val added = slot<CatalogProduct>()
        coEvery { catalogProductRepository.add(capture(added)) } answers { added.captured.copy(id = entryId) }

        val result = service.create(
            CatalogProductInput(catalogId = catalogId, productId = productId, price = Money.of("9.99")),
            principalId = null,
        )

        assertEquals(ProductType.SUBSCRIPTION, result.type)
        assertEquals(ProductType.SUBSCRIPTION, added.captured.type)
        coVerify(exactly = 1) {
            auditService.record<CatalogProduct>(eq("catalog_product"), eq(entryId), eq("created"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `edit changes the price and audits before-and-after`() = runTest {
        val id = UUID.random()
        val existing = CatalogProduct(
            id = id, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("10.00"),
        )
        coEvery { catalogProductRepository.get(id) } returns existing
        val updated = slot<CatalogProduct>()
        coEvery { catalogProductRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.edit(
            id,
            CatalogProductInput(catalogId = existing.catalogId, productId = existing.productId, price = Money.of("20.00")),
            principalId = null,
        )

        assertEquals(Money.of("20.00"), result.price)
        val before = slot<CatalogProduct>()
        val after = slot<CatalogProduct>()
        coVerify(exactly = 1) {
            auditService.record(eq("catalog_product"), eq(id), eq("updated"), any(), capture(before), capture(after), any(), any(), any(), any())
        }
        // The audit snapshots carry the correct before/after prices.
        assertEquals(Money.of("10.00"), before.captured.price)
        assertEquals(Money.of("20.00"), after.captured.price)
    }

    @Test
    fun `delete soft-deletes and audits`() = runTest {
        val id = UUID.random()
        val existing = CatalogProduct(
            id = id, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("5.00"),
        )
        coEvery { catalogProductRepository.get(id) } returns existing

        assertEquals(true, service.delete(id, principalId = null))
        coVerify(exactly = 1) { catalogProductRepository.softDelete(id) }
        coVerify(exactly = 1) {
            auditService.record<CatalogProduct>(eq("catalog_product"), eq(id), eq("deleted"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val entry = CatalogProduct(
            id = id, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("1.00"),
        )
        coEvery { catalogProductRepository.get(id) } returns entry
        assertEquals(entry, service.get(id))
    }

    @Test
    fun `get returns null when the entry is absent`() = runTest {
        val id = UUID.random()
        coEvery { catalogProductRepository.get(id) } returns null
        assertNull(service.get(id))
    }

    @Test
    fun `getByCatalog forwards all filter args`() = runTest {
        val catalogId = UUID.random()
        val list = listOf(
            CatalogProduct(catalogId = catalogId, productId = UUID.random(), type = ProductType.PHYSICAL, price = Money.of("1.00")),
        )
        coEvery { catalogProductRepository.getByCatalog(catalogId, ProductType.PHYSICAL, true, 3, 7) } returns list

        val result = service.getByCatalog(catalogId, type = ProductType.PHYSICAL, activeOnly = true, offset = 3, limit = 7)

        assertEquals(list, result)
        coVerify(exactly = 1) { catalogProductRepository.getByCatalog(catalogId, ProductType.PHYSICAL, true, 3, 7) }
    }

    @Test
    fun `create throws when the product is absent`() = runTest {
        val productId = UUID.random()
        coEvery { productService.get(productId) } returns null

        assertFailsWith<IllegalStateException> {
            service.create(
                CatalogProductInput(catalogId = UUID.random(), productId = productId, price = Money.of("9.99")),
                principalId = null,
            )
        }
        coVerify(exactly = 0) { catalogProductRepository.add(any()) }
    }

    @Test
    fun `create keeps explicit starts, ends and extras over the defaults`() = runTest {
        val productId = UUID.random()
        coEvery { productService.get(productId) } returns Product(
            id = productId, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        val added = slot<CatalogProduct>()
        coEvery { catalogProductRepository.add(capture(added)) } answers { added.captured }

        val starts = OffsetDateTime.now().plusDays(1)
        val ends = OffsetDateTime.now().plusDays(2)
        service.create(
            CatalogProductInput(
                catalogId = UUID.random(), productId = productId, price = Money.of("9.99"),
                starts = starts, ends = ends, extras = EmptyCatalogProductExtras,
            ),
            principalId = null,
        )

        // The `?:` left branches: provided starts/ends/extras win over the now()/+1000d/Empty defaults.
        assertEquals(starts, added.captured.starts)
        assertEquals(ends, added.captured.ends)
        assertEquals(EmptyCatalogProductExtras, added.captured.extras)
    }

    @Test
    fun `edit applies explicit starts, ends and extras over the existing values`() = runTest {
        val id = UUID.random()
        val existing = CatalogProduct(
            id = id, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("10.00"),
        )
        coEvery { catalogProductRepository.get(id) } returns existing
        val updated = slot<CatalogProduct>()
        coEvery { catalogProductRepository.update(capture(updated)) } answers { updated.captured }

        val starts = OffsetDateTime.now().plusDays(3)
        val ends = OffsetDateTime.now().plusDays(4)
        service.edit(
            id,
            CatalogProductInput(catalogId = existing.catalogId, productId = existing.productId, price = Money.of("12.00"), starts = starts, ends = ends, extras = EmptyCatalogProductExtras),
            principalId = null,
        )

        // The `?:` left branches: provided starts/ends/extras win over the existing row's values.
        assertEquals(starts, updated.captured.starts)
        assertEquals(ends, updated.captured.ends)
        assertEquals(EmptyCatalogProductExtras, updated.captured.extras)
    }

    @Test
    fun `edit throws when the entry is absent`() = runTest {
        val id = UUID.random()
        coEvery { catalogProductRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(id, CatalogProductInput(catalogId = UUID.random(), productId = UUID.random(), price = Money.of("1.00")), principalId = null)
        }
        coVerify(exactly = 0) { catalogProductRepository.update(any()) }
    }

    @Test
    fun `edit throws when the row vanishes mid-update`() = runTest {
        val id = UUID.random()
        val existing = CatalogProduct(
            id = id, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("10.00"),
        )
        coEvery { catalogProductRepository.get(id) } returns existing
        coEvery { catalogProductRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(id, CatalogProductInput(catalogId = existing.catalogId, productId = existing.productId, price = Money.of("20.00")), principalId = null)
        }
        coVerify(exactly = 0) {
            auditService.record<CatalogProduct>(any(), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `edit preserves existing starts, ends and extras when the input omits them`() = runTest {
        val id = UUID.random()
        val starts = OffsetDateTime.now().minusDays(5)
        val ends = OffsetDateTime.now().plusDays(5)
        val existing = CatalogProduct(
            id = id, catalogId = UUID.random(), productId = UUID.random(),
            type = ProductType.PHYSICAL, price = Money.of("10.00"), starts = starts, ends = ends,
        )
        coEvery { catalogProductRepository.get(id) } returns existing
        val updated = slot<CatalogProduct>()
        coEvery { catalogProductRepository.update(capture(updated)) } answers { updated.captured }

        // Input leaves starts/ends/extras null -> the `?:` falls back to the existing values.
        service.edit(id, CatalogProductInput(catalogId = existing.catalogId, productId = existing.productId, price = Money.of("12.00")), principalId = null)

        assertEquals(starts, updated.captured.starts)
        assertEquals(ends, updated.captured.ends)
        assertEquals(existing.extras, updated.captured.extras)
    }

    @Test
    fun `delete returns false when the entry is absent`() = runTest {
        val id = UUID.random()
        coEvery { catalogProductRepository.get(id) } returns null

        assertFalse(service.delete(id, principalId = null))
        coVerify(exactly = 0) { catalogProductRepository.softDelete(any()) }
    }

    // --- the DataLoader batch resolvers behind CatalogProduct.product / .catalog ---

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val ids = listOf(UUID.random(), UUID.random())
        val rows = listOf(entry(), entry())
        coEvery { catalogProductRepository.getByIds(ids) } returns rows
        assertEquals(rows, service.getByIds(ids))
    }

    private fun entry() = CatalogProduct(id = UUID.random(), catalogId = UUID.random(), productId = UUID.random(), type = ProductType.PHYSICAL, price = Money.of("1.00"))
    private fun product(id: UUID) = Product(id = id, companyId = UUID.random(), manufacturerId = UUID.random(), manufacturerSku = "SKU", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL)
    private fun catalog(id: UUID) = Catalog(id = id, companyId = UUID.random(), key = "k", name = "n")

    @Test
    fun `loadProductsForEntries batches with O(1) loads and skips entries whose product is missing`() = runTest {
        val cp1 = entry(); val cp2 = entry(); val cp3 = entry() // cp3's product is absent
        val p1 = product(cp1.productId); val p2 = product(cp2.productId)
        coEvery { catalogProductRepository.getByIds(any()) } returns listOf(cp1, cp2, cp3)
        coEvery { productService.getByIds(any()) } returns listOf(p1, p2) // no p3
        val batch = Batch<UUID, Product>(listOf(cp1.id, cp2.id, cp3.id))

        service.loadProductsForEntries(listOf(cp1.id, cp2.id, cp3.id), batch)

        assertEquals(p1, batch.getData(cp1.id))
        assertEquals(p2, batch.getData(cp2.id))
        assertNull(batch.getData(cp3.id)) // missing product -> not set
        // O(1): one batched entry load + one batched product load, not one per row.
        coVerify(exactly = 1) { catalogProductRepository.getByIds(any()) }
        coVerify(exactly = 1) { productService.getByIds(any()) }
    }

    @Test
    fun `loadCatalogsForEntries batches with O(1) loads and skips entries whose catalog is missing`() = runTest {
        val cp1 = entry(); val cp2 = entry(); val cp3 = entry() // cp3's catalog is absent
        val c1 = catalog(cp1.catalogId); val c2 = catalog(cp2.catalogId)
        coEvery { catalogProductRepository.getByIds(any()) } returns listOf(cp1, cp2, cp3)
        coEvery { catalogService.getByIds(any()) } returns listOf(c1, c2) // no c3
        val batch = Batch<UUID, Catalog>(listOf(cp1.id, cp2.id, cp3.id))

        service.loadCatalogsForEntries(listOf(cp1.id, cp2.id, cp3.id), batch)

        assertEquals(c1, batch.getData(cp1.id))
        assertEquals(c2, batch.getData(cp2.id))
        assertNull(batch.getData(cp3.id)) // missing catalog -> not set
        coVerify(exactly = 1) { catalogProductRepository.getByIds(any()) }
        coVerify(exactly = 1) { catalogService.getByIds(any()) }
    }
}
