package bosca.ecommerce.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductInput
import bosca.ecommerce.model.EmptyCatalogProductExtras
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.repository.CatalogProductRepository
import bosca.graphql.Batch
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Sellable catalog entries. Entry [CatalogProduct.type] is derived from the product on create. Price
 * and availability changes write audit entries (before/after) — the catalog-side "what was the price
 * then" evidence (the cart additionally snapshots prices at add-time).
 */
@ServiceImplementation
class CatalogProductServiceImpl(
    private val catalogProductRepository: CatalogProductRepository,
    private val productService: ProductService,
    private val catalogService: CatalogService,
    private val auditService: EcomAuditService,
) : CatalogProductService {

    // Caches the nested storefront sub-objects (the backing Product / Catalog) keyed by the catalog-product
    // id — the DataLoader key. These are BATCH-ONLY caches (only ever accessed via addToBatch, which uses the
    // batchResolver), so the single-key resolver is never invoked. Deliberately NOT invalidated on edit: the
    // cart-relevant data (price/availability) is a scalar resolved from the source entry, not from here; only
    // the Product/Catalog sub-objects are cached, for which the cache's default expiry is fine.
    private val productByEntry: ServiceCache<UUID, Product> =
        ServiceCache("ecom:catalog_product:product", UUIDKeySerializer, batchResolver = ::loadProductsForEntries) {
            error("ecom:catalog_product:product is batch-only — use addProductsToBatch")
        }
    private val catalogByEntry: ServiceCache<UUID, Catalog> =
        ServiceCache("ecom:catalog_product:catalog", UUIDKeySerializer, batchResolver = ::loadCatalogsForEntries) {
            error("ecom:catalog_product:catalog is batch-only — use addCatalogsToBatch")
        }

    override suspend fun get(id: UUID): CatalogProduct? = catalogProductRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<CatalogProduct> = catalogProductRepository.getByIds(ids)

    override suspend fun addProductsToBatch(batch: Batch<UUID, Product>) = productByEntry.addToBatch(batch)

    override suspend fun addCatalogsToBatch(batch: Batch<UUID, Catalog>) = catalogByEntry.addToBatch(batch)

    /** The product cache's batch resolver: re-load the entries by id, then their products in one batched query. */
    internal suspend fun loadProductsForEntries(entryIds: List<UUID>, batch: Batch<UUID, Product>) {
        val entries = catalogProductRepository.getByIds(entryIds)
        val products = productService.getByIds(entries.map { it.productId }.distinct()).associateBy { it.id }
        entries.forEach { entry -> products[entry.productId]?.let { batch.setData(entry.id, it) } }
    }

    /** The catalog cache's batch resolver: re-load the entries by id, then their catalogs in one batched query. */
    internal suspend fun loadCatalogsForEntries(entryIds: List<UUID>, batch: Batch<UUID, Catalog>) {
        val entries = catalogProductRepository.getByIds(entryIds)
        val catalogs = catalogService.getByIds(entries.map { it.catalogId }.distinct()).associateBy { it.id }
        entries.forEach { entry -> catalogs[entry.catalogId]?.let { batch.setData(entry.id, it) } }
    }

    override suspend fun getByCatalog(
        catalogId: UUID,
        type: ProductType?,
        activeOnly: Boolean,
        offset: Int,
        limit: Int,
    ): List<CatalogProduct> = catalogProductRepository.getByCatalog(catalogId, type, activeOnly, offset, limit)

    override suspend fun create(input: CatalogProductInput, principalId: UUID?): CatalogProduct = transaction {
        val product = productService.get(input.productId) ?: error("product ${input.productId} not found")
        val entry = catalogProductRepository.add(
            CatalogProduct(
                catalogId = input.catalogId,
                productId = input.productId,
                type = product.type,
                price = input.price,
                taxable = input.taxable,
                starts = input.starts ?: OffsetDateTime.now(),
                ends = input.ends ?: OffsetDateTime.now().plusDays(FAR_FUTURE_DAYS),
                extras = input.extras ?: EmptyCatalogProductExtras,
            ),
        )
        auditService.record(
            entityType = "catalog_product",
            entityId = entry.id,
            action = "created",
            serializer = CatalogProduct.serializer(),
            after = entry,
            principalId = principalId,
        )
        entry
    }

    override suspend fun edit(id: UUID, input: CatalogProductInput, principalId: UUID?): CatalogProduct = transaction {
        val existing = catalogProductRepository.get(id) ?: error("catalog product $id not found")
        val updated = catalogProductRepository.update(
            existing.copy(
                price = input.price,
                taxable = input.taxable,
                starts = input.starts ?: existing.starts,
                ends = input.ends ?: existing.ends,
                extras = input.extras ?: existing.extras,
            ),
        ) ?: error("catalog product $id not found")
        auditService.record(
            entityType = "catalog_product",
            entityId = id,
            action = "updated",
            serializer = CatalogProduct.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Boolean = transaction {
        val existing = catalogProductRepository.get(id) ?: return@transaction false
        catalogProductRepository.softDelete(id)
        auditService.record(
            entityType = "catalog_product",
            entityId = id,
            action = "deleted",
            serializer = CatalogProduct.serializer(),
            before = existing,
            principalId = principalId,
        )
        true
    }

    private companion object {
        const val FAR_FUTURE_DAYS = 1000L
    }
}
