package bosca.ecommerce.service

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductInput
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/** Sellable catalog entries (the primary storefront read path). Price/availability changes are audited. */
interface CatalogProductService : Service {

    /** A catalog entry by id. */
    suspend fun get(id: UUID): CatalogProduct?

    /** Catalog entries by id (batch — backs the nested-resolver DataLoader path). */
    suspend fun getByIds(ids: List<UUID>): List<CatalogProduct>

    /**
     * Populate a DataLoader [Batch] keyed by `CatalogProduct.id` with each entry's backing [Product],
     * through the cache layer — the storefront `CatalogProduct.product` resolver delegates here
     * so a listing costs O(1) batched loads instead of one `get` per row.
     */
    suspend fun addProductsToBatch(batch: Batch<UUID, Product>)

    /** As [addProductsToBatch], for each entry's [Catalog] (the `CatalogProduct.catalog` resolver). */
    suspend fun addCatalogsToBatch(batch: Batch<UUID, Catalog>)

    /**
     * A catalog's entries, optionally filtered by [type] and to the active availability window
     * (`starts <= now < ends`).
     */
    suspend fun getByCatalog(
        catalogId: UUID,
        type: ProductType?,
        activeOnly: Boolean,
        offset: Int,
        limit: Int,
    ): List<CatalogProduct>

    /** Place a product in a catalog as a priced, sellable entry (entry type is derived from the product). */
    suspend fun create(input: CatalogProductInput, principalId: UUID?): CatalogProduct

    /** Edit price/availability/taxable/weight/extras. Price changes are audited (before/after). */
    suspend fun edit(id: UUID, input: CatalogProductInput, principalId: UUID?): CatalogProduct

    /** Soft-delete the catalog entry. */
    suspend fun delete(id: UUID, principalId: UUID?): Boolean
}
