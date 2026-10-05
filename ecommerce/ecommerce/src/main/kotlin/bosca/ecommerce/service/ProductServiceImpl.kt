package bosca.ecommerce.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.service.MetadataService
import bosca.db.transaction
import bosca.ecommerce.events.ProductCreated
import bosca.ecommerce.events.ProductDeleted
import bosca.ecommerce.events.ProductUpdated
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.StandardProductConfiguration
import bosca.ecommerce.repository.ProductRepository
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Products backed by content Metadata. [create] provisions the Metadata document (searchable, pinned
 * at its initial version) and the `ecom.products` row in one transaction; [delete] soft-deletes both
 * together. [onContentPublished] advances the pin when a newer version of a product's document is
 * published — there is no ecom publish path.
 *
 * Search indexing reuses the content pipeline rather than adding an ecom index
 * job: products are searchable Metadata, so the commerce facets (`sku`, `manufacturer`, `type`) are
 * written onto the backing Metadata's attributes — [create] sets them, [edit] merges them (which
 * fires `MetadataUpdated` → content reindex), and [delete]'s `markDeleted` drops the document.
 */
@ServiceImplementation
class ProductServiceImpl(
    private val productRepository: ProductRepository,
    private val metadataService: MetadataService,
    private val manufacturerService: ManufacturerService,
    private val companyService: CompanyService,
    private val auditService: EcomAuditService,
) : ProductService {

    // Caches the nested storefront sub-objects (the backing Company / Manufacturer) keyed by the product
    // id — the DataLoader key. These are BATCH-ONLY caches (only ever accessed via addToBatch, which uses
    // the batchResolver), so the single-key resolver is never invoked.
    private val companyByProduct: ServiceCache<UUID, Company> =
        ServiceCache("ecom:product:company", UUIDKeySerializer, batchResolver = ::loadCompaniesForProducts) {
            error("ecom:product:company is batch-only — use addCompaniesToBatch")
        }
    private val manufacturerByProduct: ServiceCache<UUID, Manufacturer> =
        ServiceCache("ecom:product:manufacturer", UUIDKeySerializer, batchResolver = ::loadManufacturersForProducts) {
            error("ecom:product:manufacturer is batch-only — use addManufacturersToBatch")
        }

    override suspend fun get(id: UUID): Product? = productRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Product> = productRepository.getByIds(ids)

    override suspend fun addCompaniesToBatch(batch: Batch<UUID, Company>) = companyByProduct.addToBatch(batch)

    override suspend fun addManufacturersToBatch(batch: Batch<UUID, Manufacturer>) =
        manufacturerByProduct.addToBatch(batch)

    /** The company cache's batch resolver: re-load the products by id, then their companies in one batched query. */
    internal suspend fun loadCompaniesForProducts(productIds: List<UUID>, batch: Batch<UUID, Company>) {
        val products = productRepository.getByIds(productIds)
        val companies = companyService.getByIds(products.map { it.companyId }.distinct()).associateBy { it.id }
        products.forEach { product -> companies[product.companyId]?.let { batch.setData(product.id, it) } }
    }

    /** The manufacturer cache's batch resolver: re-load the products by id, then their manufacturers in one batched query. */
    internal suspend fun loadManufacturersForProducts(productIds: List<UUID>, batch: Batch<UUID, Manufacturer>) {
        val products = productRepository.getByIds(productIds)
        val manufacturers = manufacturerService.getByIds(products.map { it.manufacturerId }.distinct()).associateBy { it.id }
        products.forEach { product -> manufacturers[product.manufacturerId]?.let { batch.setData(product.id, it) } }
    }

    override suspend fun getByMetadataId(metadataId: UUID): Product? = productRepository.getByMetadataId(metadataId)

    override suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Product> =
        productRepository.getByCompany(companyId, offset, limit)

    override suspend fun create(input: ProductInput, principalId: UUID?): Product = transaction {
        val manufacturer = manufacturerService.get(input.manufacturerId)
        val commerce = commerceAttributes(input.manufacturerSku, input.type, manufacturer?.name)
        val attributes = buildJsonObject {
            input.description?.let { put("description", it) }
            commerce.forEach { (key, value) -> put(key, value) }
        }
        val metadata = metadataService.add(
            null,
            null,
            MetadataInput(
                name = input.title,
                languageTag = DEFAULT_LANGUAGE,
                contentType = PRODUCT_CONTENT_TYPE,
                attributes = attributes,
                searchable = true,
            ),
        )
        val product = productRepository.add(
            Product(
                companyId = input.companyId,
                manufacturerId = input.manufacturerId,
                manufacturerSku = input.manufacturerSku,
                metadataId = metadata.id,
                metadataVersion = metadata.version,
                type = input.type,
                configuration = input.configuration ?: StandardProductConfiguration,
                weight = input.weight,
                width = input.width,
                height = input.height,
                length = input.length,
            ),
        )
        auditService.record(
            entityType = "product",
            entityId = product.id,
            action = "created",
            serializer = Product.serializer(),
            after = product,
            principalId = principalId,
        )
        ProductCreated(productId = product.id, companyId = product.companyId).dispatch()
        product
    }

    override suspend fun edit(id: UUID, input: ProductInput, principalId: UUID?): Product = transaction {
        val existing = productRepository.get(id) ?: error("product $id not found")
        val updated = productRepository.update(
            existing.copy(
                manufacturerId = input.manufacturerId,
                manufacturerSku = input.manufacturerSku,
                type = input.type,
                configuration = input.configuration ?: existing.configuration,
                weight = input.weight,
                width = input.width,
                height = input.height,
                length = input.length,
            ),
        ) ?: error("product $id not found")
        // Sync the commerce facets onto the backing Metadata so the search document stays current.
        // mergeAttributes deep-merges (author attributes/description are preserved) and fires
        // MetadataUpdated, which the content indexer consumes to reindex the document.
        val manufacturer = manufacturerService.get(updated.manufacturerId)
        metadataService.getById(updated.metadataId, updated.metadataVersion)?.let { metadata ->
            metadataService.mergeAttributes(
                metadata,
                commerceAttributes(updated.manufacturerSku, updated.type, manufacturer?.name),
            )
        }
        auditService.record(
            entityType = "product",
            entityId = id,
            action = "updated",
            serializer = Product.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        ProductUpdated(productId = updated.id, companyId = updated.companyId).dispatch()
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = productRepository.get(id) ?: return@transaction false
        productRepository.softDelete(id)
        metadataService.markDeleted(existing.metadataId)
        auditService.record(
            entityType = "product",
            entityId = id,
            action = "deleted",
            serializer = Product.serializer(),
            before = existing,
            principalId = principalId,
        )
        ProductDeleted(productId = existing.id, companyId = existing.companyId).dispatch()
        true
    }

    override suspend fun onContentPublished(metadataId: UUID, version: Int) {
        transaction {
            val product = productRepository.getByMetadataId(metadataId) ?: return@transaction
            if (version <= product.metadataVersion) return@transaction
            val updated = productRepository.updatePin(product.id, version) ?: return@transaction
            auditService.record(
                entityType = "product",
                entityId = product.id,
                action = "content_published",
                serializer = Product.serializer(),
                before = product,
                after = updated,
                details = buildJsonObject {
                    put("metadataId", metadataId.toString())
                    put("version", version)
                },
            )
        }
    }

    /**
     * The commerce facets carried on a product's backing Metadata so they flow into the search
     * document: the manufacturer SKU, the product type, and the manufacturer's display name
     * (omitted when the manufacturer can't be resolved).
     */
    private fun commerceAttributes(sku: String, type: ProductType, manufacturerName: String?): JsonObject =
        buildJsonObject {
            put("sku", sku)
            put("type", type.name)
            manufacturerName?.let { put("manufacturer", it) }
        }

    private companion object {
        const val PRODUCT_CONTENT_TYPE = "bosca/v-document"
        const val DEFAULT_LANGUAGE = "en"
    }
}
