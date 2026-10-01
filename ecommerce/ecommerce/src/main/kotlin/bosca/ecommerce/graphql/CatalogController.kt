package bosca.ecommerce.graphql

import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.CatalogService
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Catalog` GraphQL type. */
@TypeController
class CatalogController(
    private val catalogService: CatalogService,
    private val catalogProductService: CatalogProductService,
) : GraphQLController<Catalog> {

    @Field
    fun id(source: Catalog): UUID = source.id

    // Batched via the DataLoader: the dispatcher keys this loader by `Catalog.id` and invokes the resolver
    // ONCE per request with all the catalog ids that need a `company`, so a listing costs O(1) cached/batched
    // loads instead of one `get` per row. The batch + cache live in the service; the resolver just
    // delegates.
    @Field
    suspend fun company(batch: Batch<UUID, Company>) = catalogService.addCompaniesToBatch(batch)

    @Field
    fun key(source: Catalog): String = source.key

    @Field
    fun name(source: Catalog): String = source.name

    @Field
    fun currency(source: Catalog): String = source.currency

    @Field
    suspend fun products(
        source: Catalog,
        type: ProductType?,
        activeOnly: Boolean,
        offset: Int,
        limit: Int,
    ): List<CatalogProduct> = catalogProductService.getByCatalog(source.id, type, activeOnly, offset, limit)

    @Field
    fun created(source: Catalog): OffsetDateTime = source.created

    @Field
    fun modified(source: Catalog): OffsetDateTime = source.modified
}
