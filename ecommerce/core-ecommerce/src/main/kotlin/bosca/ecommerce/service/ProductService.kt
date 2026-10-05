package bosca.ecommerce.service

import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductInput
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Products — commerce rows backed by a content Metadata document. [create] provisions the Metadata
 * and pins version 1; [edit] changes commerce fields only; [delete] soft-deletes the row and the
 * Metadata together. [onContentPublished] is the reaction to a content publish: it advances the
 * pinned version (there is no ecom publish mutation — publishing flows from content).
 */
interface ProductService : Service {

    /** A product by id. */
    suspend fun get(id: UUID): Product?

    /** Products by id (batch — backs the nested-resolver DataLoader path). */
    suspend fun getByIds(ids: List<UUID>): List<Product>

    /**
     * Populate a DataLoader [Batch] keyed by `Product.id` with each product's [Company], through the
     * cache layer — the `Product.company` resolver delegates here so a listing costs O(1)
     * batched loads instead of one `get` per row.
     */
    suspend fun addCompaniesToBatch(batch: Batch<UUID, Company>)

    /** As [addCompaniesToBatch], for each product's [Manufacturer] (the `Product.manufacturer` resolver). */
    suspend fun addManufacturersToBatch(batch: Batch<UUID, Manufacturer>)

    /** The product backed by a given content Metadata, or null if none (not an ecom product). */
    suspend fun getByMetadataId(metadataId: UUID): Product?

    /** A company's products, paged. */
    suspend fun getByCompany(companyId: UUID, offset: Int, limit: Int): List<Product>

    /** Create a product and its backing content Metadata document (pinned at version 1). */
    suspend fun create(input: ProductInput, principalId: UUID?): Product

    /** Edit the product's commerce fields and configuration (not its content — that flows from content). */
    suspend fun edit(id: UUID, input: ProductInput, principalId: UUID?): Product

    /** Soft-delete the product and its backing Metadata together. */
    suspend fun delete(id: UUID, principalId: UUID?): Boolean

    /**
     * React to a content document publish: if [metadataId] backs a product and [version] is newer
     * than the current pin, advance `metadata_version` to [version] and audit it (action
     * `content_published`). No-op if the metadata isn't an ecom product or the version isn't newer.
     * The caller is the content-publish signal wiring (composition root) — ecom owns the reaction,
     * not the subscription.
     */
    suspend fun onContentPublished(metadataId: UUID, version: Int)
}
