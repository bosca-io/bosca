@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.cache.CacheManager
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.ClothingProductConfiguration
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.repository.ProductRepository
import bosca.graphql.Batch
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * product orchestration. `create` provisions the backing Metadata and pins it; `delete`
 * soft-deletes row + Metadata together; `onContentPublished` advances the pin only for an owned
 * metadata whose published version is newer.
 */
@OptIn(ExperimentalUuidApi::class)
class ProductServiceImplTest {

    private val productRepository = mockk<ProductRepository>(relaxUnitFun = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val manufacturerService = mockk<ManufacturerService>(relaxed = true)
    private val companyService = mockk<CompanyService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: ProductServiceImpl

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        // The service builds batch-only ServiceCaches at construction (which resolve a CacheManager from DI).
        provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
        service = ProductServiceImpl(productRepository, metadataService, manufacturerService, companyService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    @Test
    fun `create provisions the backing metadata, pins it, and audits`() = runTest {
        val companyId = UUID.random()
        val manufacturerId = UUID.random()
        val metadataId = UUID.random()
        val productId = UUID.random()

        val metadata = mockk<Metadata> {
            every { id } returns metadataId
            every { version } returns 1
        }
        val inputSlot = slot<MetadataInput>()
        coEvery { metadataService.add(any(), any(), capture(inputSlot)) } returns metadata
        coEvery { manufacturerService.get(manufacturerId) } returns Manufacturer(
            id = manufacturerId, companyId = companyId, name = "Acme",
        )
        val product = Product(
            id = productId, companyId = companyId, manufacturerId = manufacturerId,
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.add(any()) } returns product

        val result = service.create(
            ProductInput(
                companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU1",
                type = ProductType.PHYSICAL, title = "Widget", description = "A widget",
            ),
            principalId = null,
        )

        assertEquals(product, result)
        coVerify(exactly = 1) { metadataService.add(any(), any(), any()) }
        coVerify(exactly = 1) {
            productRepository.add(match { it.metadataId == metadataId && it.metadataVersion == 1 && it.manufacturerSku == "SKU1" })
        }
        coVerify(exactly = 1) { auditService.record<Product>(eq("product"), eq(productId), eq("created"), any(), any(), any(), any(), any(), any(), any()) }

        // the backing Metadata is searchable and carries the commerce facets that flow into
        // the search document, alongside the author-provided description.
        assertEquals(true, inputSlot.captured.searchable)
        val attrs = inputSlot.captured.attributes.jsonObject
        assertEquals("SKU1", attrs.getValue("sku").jsonPrimitive.content)
        assertEquals("PHYSICAL", attrs.getValue("type").jsonPrimitive.content)
        assertEquals("Acme", attrs.getValue("manufacturer").jsonPrimitive.content)
        assertEquals("A widget", attrs.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun `edit syncs commerce facets onto the backing metadata to trigger reindex`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val manufacturerId = UUID.random()
        val existing = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "OLD", metadataId = metadataId, metadataVersion = 2, type = ProductType.PHYSICAL,
        )
        val updated = existing.copy(manufacturerId = manufacturerId, manufacturerSku = "NEW", type = ProductType.VIRTUAL)
        coEvery { productRepository.get(id) } returns existing
        coEvery { productRepository.update(any()) } returns updated
        coEvery { manufacturerService.get(manufacturerId) } returns Manufacturer(
            id = manufacturerId, companyId = existing.companyId, name = "Globex",
        )
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId, 2) } returns metadata
        val attrsSlot = slot<kotlinx.serialization.json.JsonElement>()
        coEvery { metadataService.mergeAttributes(metadata, capture(attrsSlot)) } returns Unit

        service.edit(
            id,
            ProductInput(
                companyId = existing.companyId, manufacturerId = manufacturerId, manufacturerSku = "NEW",
                type = ProductType.VIRTUAL, title = "Widget",
            ),
            principalId = null,
        )

        coVerify(exactly = 1) { metadataService.mergeAttributes(metadata, any()) }
        val attrs = attrsSlot.captured.jsonObject
        assertEquals("NEW", attrs.getValue("sku").jsonPrimitive.content)
        assertEquals("VIRTUAL", attrs.getValue("type").jsonPrimitive.content)
        assertEquals("Globex", attrs.getValue("manufacturer").jsonPrimitive.content)
    }

    @Test
    fun `onContentPublished advances the pin when the published version is newer`() = runTest {
        val metadataId = UUID.random()
        val productId = UUID.random()
        val product = Product(
            id = productId, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.getByMetadataId(metadataId) } returns product
        val pinned = slot<Int>()
        coEvery { productRepository.updatePin(productId, capture(pinned)) } answers { product.copy(metadataVersion = pinned.captured) }

        service.onContentPublished(metadataId, 2)

        assertEquals(2, pinned.captured)
        coVerify(exactly = 1) { auditService.record<Product>(eq("product"), eq(productId), eq("content_published"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onContentPublished is a no-op for a metadata that is not an ecom product`() = runTest {
        val metadataId = UUID.random()
        coEvery { productRepository.getByMetadataId(metadataId) } returns null

        service.onContentPublished(metadataId, 5)

        coVerify(exactly = 0) { productRepository.updatePin(any(), any()) }
        coVerify(exactly = 0) { auditService.record<Product>(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onContentPublished is a no-op when the version is not newer than the pin`() = runTest {
        val metadataId = UUID.random()
        val product = Product(
            id = UUID.random(), companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 3, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.getByMetadataId(metadataId) } returns product

        service.onContentPublished(metadataId, 2)

        coVerify(exactly = 0) { productRepository.updatePin(any(), any()) }
    }

    @Test
    fun `delete soft-deletes the product and its metadata together`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val product = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.get(id) } returns product

        val deleted = service.delete(id, principalId = null)

        assertEquals(true, deleted)
        coVerify(exactly = 1) { productRepository.softDelete(id) }
        coVerify(exactly = 1) { metadataService.markDeleted(metadataId) }
        coVerify(exactly = 1) { auditService.record<Product>(eq("product"), eq(id), eq("deleted"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `delete returns false when the product is absent`() = runTest {
        val id = UUID.random()
        coEvery { productRepository.get(id) } returns null
        assertEquals(false, service.delete(id, principalId = null))
        coVerify(exactly = 0) { productRepository.softDelete(any()) }
        coVerify(exactly = 0) { metadataService.markDeleted(any()) }
    }

    @Test
    fun `edit still updates the product when the backing metadata version is absent`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val existing = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "OLD", metadataId = metadataId, metadataVersion = 2, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.get(id) } returns existing
        coEvery { productRepository.update(any()) } answers { firstArg() }
        // Metadata version no longer resolvable -> the merge is skipped (the `?.let` null branch).
        coEvery { metadataService.getById(metadataId, 2) } returns null

        service.edit(id, ProductInput(companyId = existing.companyId, manufacturerId = existing.manufacturerId, manufacturerSku = "NEW", type = ProductType.PHYSICAL, title = "Widget"), principalId = null)

        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any()) }
        coVerify(exactly = 1) { auditService.record<Product>(eq("product"), eq(id), eq("updated"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onContentPublished is a no-op when the pin update finds no row`() = runTest {
        val metadataId = UUID.random()
        val product = Product(
            id = UUID.random(), companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.getByMetadataId(metadataId) } returns product
        coEvery { productRepository.updatePin(product.id, 2) } returns null

        service.onContentPublished(metadataId, 2)

        coVerify(exactly = 0) { auditService.record<Product>(any(), any(), eq("content_published"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `get delegates to the repository`() = runTest {
        val id = UUID.random()
        val product = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.get(id) } returns product
        assertEquals(product, service.get(id))
    }

    @Test
    fun `get returns null when the product is absent`() = runTest {
        val id = UUID.random()
        coEvery { productRepository.get(id) } returns null
        assertNull(service.get(id))
    }

    @Test
    fun `getByIds delegates to the repository`() = runTest {
        val id = UUID.random()
        val product = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.getByIds(listOf(id)) } returns listOf(product)
        assertEquals(listOf(product), service.getByIds(listOf(id)))
    }

    @Test
    fun `getByMetadataId delegates to the repository`() = runTest {
        val metadataId = UUID.random()
        val product = Product(
            id = UUID.random(), companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.getByMetadataId(metadataId) } returns product
        assertEquals(product, service.getByMetadataId(metadataId))
    }

    @Test
    fun `getByCompany delegates with paging args`() = runTest {
        val companyId = UUID.random()
        val list = listOf(
            Product(
                id = UUID.random(), companyId = companyId, manufacturerId = UUID.random(),
                manufacturerSku = "SKU1", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
            ),
        )
        coEvery { productRepository.getByCompany(companyId, 2, 4) } returns list

        assertEquals(list, service.getByCompany(companyId, offset = 2, limit = 4))
        coVerify(exactly = 1) { productRepository.getByCompany(companyId, 2, 4) }
    }

    @Test
    fun `create omits the manufacturer facet and description when both are unresolved`() = runTest {
        val companyId = UUID.random()
        val manufacturerId = UUID.random()
        val metadataId = UUID.random()
        val productId = UUID.random()

        val metadata = mockk<Metadata> {
            every { id } returns metadataId
            every { version } returns 1
        }
        val inputSlot = slot<MetadataInput>()
        coEvery { metadataService.add(any(), any(), capture(inputSlot)) } returns metadata
        // Manufacturer can't be resolved -> the `manufacturerName?.let` branch is skipped.
        coEvery { manufacturerService.get(manufacturerId) } returns null
        coEvery { productRepository.add(any()) } returns Product(
            id = productId, companyId = companyId, manufacturerId = manufacturerId,
            manufacturerSku = "SKU1", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )

        // No description -> the `input.description?.let` branch is skipped.
        service.create(
            ProductInput(
                companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU1",
                type = ProductType.PHYSICAL, title = "Widget", description = null,
            ),
            principalId = null,
        )

        val attrs = inputSlot.captured.attributes.jsonObject
        assertEquals("SKU1", attrs.getValue("sku").jsonPrimitive.content)
        assertEquals("PHYSICAL", attrs.getValue("type").jsonPrimitive.content)
        assertFalse(attrs.containsKey("manufacturer"))
        assertFalse(attrs.containsKey("description"))
    }

    @Test
    fun `edit throws when the product is absent`() = runTest {
        val id = UUID.random()
        coEvery { productRepository.get(id) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(id, ProductInput(companyId = UUID.random(), manufacturerId = UUID.random(), manufacturerSku = "X", type = ProductType.PHYSICAL, title = "Widget"), principalId = null)
        }
        coVerify(exactly = 0) { productRepository.update(any()) }
    }

    @Test
    fun `edit throws when the row vanishes mid-update`() = runTest {
        val id = UUID.random()
        val existing = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "OLD", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.get(id) } returns existing
        coEvery { productRepository.update(any()) } returns null

        assertFailsWith<IllegalStateException> {
            service.edit(id, ProductInput(companyId = existing.companyId, manufacturerId = existing.manufacturerId, manufacturerSku = "NEW", type = ProductType.PHYSICAL, title = "Widget"), principalId = null)
        }
        coVerify(exactly = 0) { metadataService.mergeAttributes(any(), any()) }
        coVerify(exactly = 0) {
            auditService.record<Product>(any(), any(), eq("updated"), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `create applies an explicit product configuration over the default`() = runTest {
        val companyId = UUID.random()
        val manufacturerId = UUID.random()
        val metadataId = UUID.random()
        val metadata = mockk<Metadata> {
            every { id } returns metadataId
            every { version } returns 1
        }
        coEvery { metadataService.add(any(), any(), any()) } returns metadata
        coEvery { manufacturerService.get(manufacturerId) } returns Manufacturer(id = manufacturerId, companyId = companyId, name = "Acme")
        val captured = slot<Product>()
        coEvery { productRepository.add(capture(captured)) } answers { captured.captured }

        val config = ClothingProductConfiguration(sizes = listOf("S", "M"))
        service.create(
            ProductInput(companyId = companyId, manufacturerId = manufacturerId, manufacturerSku = "SKU", type = ProductType.PHYSICAL, title = "W", configuration = config),
            principalId = null,
        )

        assertEquals(config, captured.captured.configuration) // `?:` left branch: explicit config wins over StandardProductConfiguration
    }

    @Test
    fun `edit applies an explicit product configuration over the existing one`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val existing = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "OLD", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        val captured = slot<Product>()
        coEvery { productRepository.get(id) } returns existing
        coEvery { productRepository.update(capture(captured)) } answers { captured.captured }
        coEvery { metadataService.getById(metadataId, 1) } returns null

        val config = ClothingProductConfiguration(sizes = listOf("L"))
        service.edit(
            id,
            ProductInput(companyId = existing.companyId, manufacturerId = existing.manufacturerId, manufacturerSku = "NEW", type = ProductType.PHYSICAL, title = "W", configuration = config),
            principalId = null,
        )

        assertEquals(config, captured.captured.configuration) // `?:` left branch: explicit config wins over existing
    }

    @Test
    fun `edit omits the manufacturer facet when the manufacturer is unresolved`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val manufacturerId = UUID.random()
        val existing = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "OLD", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        coEvery { productRepository.get(id) } returns existing
        coEvery { productRepository.update(any()) } answers { firstArg() }
        coEvery { manufacturerService.get(any()) } returns null // manufacturer?.name -> null arm
        val metadata = mockk<Metadata>()
        coEvery { metadataService.getById(metadataId, 1) } returns metadata
        coEvery { metadataService.mergeAttributes(metadata, any()) } returns Unit

        service.edit(
            id,
            ProductInput(companyId = existing.companyId, manufacturerId = manufacturerId, manufacturerSku = "NEW", type = ProductType.PHYSICAL, title = "W"),
            principalId = null,
        )

        coVerify(exactly = 1) { metadataService.mergeAttributes(metadata, any()) }
    }

    @Test
    fun `edit keeps the existing configuration when the input omits it`() = runTest {
        val id = UUID.random()
        val metadataId = UUID.random()
        val existing = Product(
            id = id, companyId = UUID.random(), manufacturerId = UUID.random(),
            manufacturerSku = "OLD", metadataId = metadataId, metadataVersion = 1, type = ProductType.PHYSICAL,
        )
        val captured = slot<Product>()
        coEvery { productRepository.get(id) } returns existing
        coEvery { productRepository.update(capture(captured)) } answers { captured.captured }
        coEvery { metadataService.getById(metadataId, 1) } returns null

        // input.configuration is null -> the `?:` keeps existing.configuration.
        val result = service.edit(id, ProductInput(companyId = existing.companyId, manufacturerId = existing.manufacturerId, manufacturerSku = "NEW", type = ProductType.PHYSICAL, title = "Widget"), principalId = null)

        assertEquals(existing.configuration, captured.captured.configuration)
        assertEquals("NEW", result.manufacturerSku)
    }

    // --- the DataLoader batch resolvers behind Product.company / .manufacturer ---

    private fun product(companyId: UUID = UUID.random(), manufacturerId: UUID = UUID.random()) = Product(
        id = UUID.random(), companyId = companyId, manufacturerId = manufacturerId,
        manufacturerSku = "SKU", metadataId = UUID.random(), metadataVersion = 1, type = ProductType.PHYSICAL,
    )

    @Test
    fun `loadCompaniesForProducts batches with O(1) loads and skips products whose company is missing`() = runTest {
        val p1 = product(); val p2 = product(); val p3 = product() // p3's company is absent
        val c1 = Company(id = p1.companyId, organizationId = UUID.random(), profileId = UUID.random())
        val c2 = Company(id = p2.companyId, organizationId = UUID.random(), profileId = UUID.random())
        coEvery { productRepository.getByIds(any()) } returns listOf(p1, p2, p3)
        coEvery { companyService.getByIds(any()) } returns listOf(c1, c2) // no c3
        val batch = Batch<UUID, Company>(listOf(p1.id, p2.id, p3.id))

        service.loadCompaniesForProducts(listOf(p1.id, p2.id, p3.id), batch)

        assertEquals(c1, batch.getData(p1.id))
        assertEquals(c2, batch.getData(p2.id))
        assertNull(batch.getData(p3.id)) // missing company -> not set
        // O(1): one batched product load + one batched company load, not one per row.
        coVerify(exactly = 1) { productRepository.getByIds(any()) }
        coVerify(exactly = 1) { companyService.getByIds(any()) }
    }

    @Test
    fun `loadManufacturersForProducts batches with O(1) loads and skips products whose manufacturer is missing`() = runTest {
        val p1 = product(); val p2 = product(); val p3 = product() // p3's manufacturer is absent
        val m1 = Manufacturer(id = p1.manufacturerId, companyId = p1.companyId, name = "Acme")
        val m2 = Manufacturer(id = p2.manufacturerId, companyId = p2.companyId, name = "Globex")
        coEvery { productRepository.getByIds(any()) } returns listOf(p1, p2, p3)
        coEvery { manufacturerService.getByIds(any()) } returns listOf(m1, m2) // no m3
        val batch = Batch<UUID, Manufacturer>(listOf(p1.id, p2.id, p3.id))

        service.loadManufacturersForProducts(listOf(p1.id, p2.id, p3.id), batch)

        assertEquals(m1, batch.getData(p1.id))
        assertEquals(m2, batch.getData(p2.id))
        assertNull(batch.getData(p3.id)) // missing manufacturer -> not set
        coVerify(exactly = 1) { productRepository.getByIds(any()) }
        coVerify(exactly = 1) { manufacturerService.getByIds(any()) }
    }
}
