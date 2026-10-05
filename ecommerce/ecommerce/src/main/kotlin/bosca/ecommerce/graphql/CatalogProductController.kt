package bosca.ecommerce.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.ecommerce.model.Catalog
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.CatalogProductExtras
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.CatalogProductService
import bosca.ecommerce.service.ProductService
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Field wiring for the `CatalogProduct` GraphQL type. [metadata] is the storefront convenience for
 * the backing product's content document at the product's pinned version.
 */
@TypeController
class CatalogProductController(
    private val catalogProductService: CatalogProductService,
    private val productService: ProductService,
    private val metadataService: MetadataService,
) : GraphQLController<CatalogProduct> {

    @Field
    fun id(source: CatalogProduct): UUID = source.id

    // Batched via the DataLoader: the dispatcher keys this loader by `CatalogProduct.id` (@BatchKey("id"))
    // and invokes the resolver ONCE per request with all the catalog-product ids that need a `catalog`, so a
    // 25-item storefront listing costs O(1) cached/batched loads instead of one `get` per row. The
    // batch + cache live in the service; the resolver just delegates.
    @Field
    suspend fun catalog(batch: Batch<UUID, Catalog>) = catalogProductService.addCatalogsToBatch(batch)

    @Field
    suspend fun product(batch: Batch<UUID, Product>) = catalogProductService.addProductsToBatch(batch)

    // NOT batched: metadata is the product's content at its PINNED [Product.metadataVersion]; the content
    // MetadataService only batches by id (returning the *latest* version), which would serve the wrong
    // (possibly unpublished) version. Batching this needs a content-side batch-by-(id, version) — left as a
    // cross-module follow-up.
    @Field
    suspend fun metadata(source: CatalogProduct): Metadata {
        val product = productService.get(source.productId) ?: error("product ${source.productId} not found")
        return metadataService.getById(product.metadataId, product.metadataVersion)
            ?: error("metadata ${product.metadataId} not found")
    }

    @Field
    fun type(source: CatalogProduct): ProductType = source.type

    @Field
    fun price(source: CatalogProduct): Money = source.price

    @Field
    fun taxable(source: CatalogProduct): Boolean = source.taxable

    @Field
    fun starts(source: CatalogProduct): OffsetDateTime = source.starts

    @Field
    fun ends(source: CatalogProduct): OffsetDateTime = source.ends

    @Field
    fun promotions(source: CatalogProduct): List<String> = source.promotions

    @Field
    fun extras(source: CatalogProduct): CatalogProductExtras = source.extras
}
