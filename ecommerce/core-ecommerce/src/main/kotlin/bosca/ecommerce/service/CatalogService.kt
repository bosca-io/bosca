package bosca.ecommerce.service

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogInput
import bosca.ecommerce.model.Company
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/** Catalogs — priceable groupings of products. [CatalogInput.key] is unique per company. */
interface CatalogService : Service {

    /** A catalog by id. */
    suspend fun get(id: UUID): Catalog?

    /** Catalogs by id (batch — backs the nested-resolver DataLoader path). */
    suspend fun getByIds(ids: List<UUID>): List<Catalog>

    /**
     * Populate a DataLoader [Batch] keyed by `Catalog.id` with each catalog's owning [Company],
     * through the cache layer — the storefront `Catalog.company` resolver delegates here
     * so a listing costs O(1) batched loads instead of one `get` per row.
     */
    suspend fun addCompaniesToBatch(batch: Batch<UUID, Company>)

    /** A catalog by its per-company business key. */
    suspend fun getByKey(companyId: UUID, key: String): Catalog?

    /** A company's catalogs. */
    suspend fun getByCompany(companyId: UUID): List<Catalog>

    /** Create a catalog. */
    suspend fun create(input: CatalogInput, principalId: UUID?): Catalog

    /** Edit a catalog's key/name. */
    suspend fun edit(id: UUID, input: CatalogInput, principalId: UUID?): Catalog
}
