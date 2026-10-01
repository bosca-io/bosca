package bosca.ecommerce.service

import bosca.cache.ServiceCache
import bosca.cache.serializers.UUIDKeySerializer
import bosca.db.transaction
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogInput
import bosca.ecommerce.model.Company
import bosca.ecommerce.repository.CatalogRepository
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Catalogs. Create/edit are audited; `(company_id, key)` uniqueness is enforced by the DDL. */
@ServiceImplementation
class CatalogServiceImpl(
    private val catalogRepository: CatalogRepository,
    private val companyService: CompanyService,
    private val auditService: EcomAuditService,
) : CatalogService {

    // Caches the nested storefront sub-object (the owning Company) keyed by the catalog id — the DataLoader
    // key. This is a BATCH-ONLY cache (only ever accessed via addToBatch, which uses the batchResolver), so
    // the single-key resolver is never invoked.
    private val companyByCatalog: ServiceCache<UUID, Company> =
        ServiceCache("ecom:catalog:company", UUIDKeySerializer, batchResolver = ::loadCompaniesForCatalogs) {
            error("ecom:catalog:company is batch-only — use addCompaniesToBatch")
        }

    override suspend fun get(id: UUID): Catalog? = catalogRepository.get(id)

    override suspend fun getByIds(ids: List<UUID>): List<Catalog> = catalogRepository.getByIds(ids)

    override suspend fun addCompaniesToBatch(batch: Batch<UUID, Company>) = companyByCatalog.addToBatch(batch)

    /** The company cache's batch resolver: re-load the catalogs by id, then their companies in one batched query. */
    internal suspend fun loadCompaniesForCatalogs(catalogIds: List<UUID>, batch: Batch<UUID, Company>) {
        val catalogs = catalogRepository.getByIds(catalogIds)
        val companies = companyService.getByIds(catalogs.map { it.companyId }.distinct()).associateBy { it.id }
        catalogs.forEach { c -> companies[c.companyId]?.let { batch.setData(c.id, it) } }
    }

    override suspend fun getByKey(companyId: UUID, key: String): Catalog? = catalogRepository.getByKey(companyId, key)

    override suspend fun getByCompany(companyId: UUID): List<Catalog> = catalogRepository.getByCompany(companyId)

    override suspend fun create(input: CatalogInput, principalId: UUID?): Catalog = transaction {
        val catalog = catalogRepository.add(Catalog(companyId = input.companyId, key = input.key, name = input.name, currency = input.currency))
        auditService.record(
            entityType = "catalog",
            entityId = catalog.id,
            action = "created",
            serializer = Catalog.serializer(),
            after = catalog,
            principalId = principalId,
        )
        catalog
    }

    override suspend fun edit(id: UUID, input: CatalogInput, principalId: UUID?): Catalog = transaction {
        val existing = catalogRepository.get(id) ?: error("catalog $id not found")
        // A catalog's currency is the immutable price-book anchor — changing it would silently re-denominate
        // every price in the book. The edit path doesn't persist it; reject an actual change rather than
        // ignoring the supplied value. (Editors echo the existing currency, so a no-op passes.)
        check(input.currency == existing.currency) {
            "catalog $id currency is immutable (${existing.currency}); it cannot be changed to ${input.currency}"
        }
        val updated = catalogRepository.update(existing.copy(key = input.key, name = input.name))
            ?: error("catalog $id not found")
        auditService.record(
            entityType = "catalog",
            entityId = id,
            action = "updated",
            serializer = Catalog.serializer(),
            before = existing,
            after = updated,
            principalId = principalId,
        )
        updated
    }
}
