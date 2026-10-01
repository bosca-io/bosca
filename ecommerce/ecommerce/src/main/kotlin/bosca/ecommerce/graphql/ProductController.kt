package bosca.ecommerce.graphql

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Manufacturer
import bosca.ecommerce.model.Product
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.service.InventoryService
import bosca.ecommerce.service.ProductService
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.OffsetDateTime
import bosca.ecommerce.model.ProductConfiguration
import bosca.serialization.UUID

/**
 * Field wiring for the `Product` GraphQL type. [metadata] resolves the backing content document at
 * the product's PINNED version (drafts never appear). [inventory] is operational data — admin-gated.
 */
@TypeController
class ProductController(
    private val productService: ProductService,
    private val metadataService: MetadataService,
    private val inventoryService: InventoryService,
    private val groups: GroupEvaluator,
) : GraphQLController<Product> {

    @Field
    fun id(source: Product): UUID = source.id

    // Batched via the DataLoader: the dispatcher keys this loader by `Product.id` and invokes the resolver
    // ONCE per request with all the product ids that need a `company`, so a listing costs O(1) cached/batched
    // loads instead of one `get` per row. The batch + cache live in the service; the resolver delegates.
    @Field
    suspend fun company(batch: Batch<UUID, Company>) = productService.addCompaniesToBatch(batch)

    @Field
    suspend fun manufacturer(batch: Batch<UUID, Manufacturer>) = productService.addManufacturersToBatch(batch)

    @Field
    fun manufacturerSku(source: Product): String = source.manufacturerSku

    @Field
    fun type(source: Product): ProductType = source.type

    @Field
    fun configuration(source: Product): ProductConfiguration = source.configuration

    @Field
    fun weight(source: Product): Double = source.weight

    @Field
    fun width(source: Product): Double = source.width

    @Field
    fun height(source: Product): Double = source.height

    @Field
    fun length(source: Product): Double = source.length

    @Field
    suspend fun metadata(source: Product): Metadata =
        metadataService.getById(source.metadataId, source.metadataVersion)
            ?: error("metadata ${source.metadataId} v${source.metadataVersion} not found")

    @Field
    fun metadataVersion(source: Product): Int = source.metadataVersion

    /** Inventory rows across fulfillment centers (operational data — admin). */
    @Field
    suspend fun inventory(authentication: AuthenticationContext, source: Product): List<Inventory> {
        groups.verifyEcomAdmin(authentication)
        return inventoryService.getByProduct(source.id)
    }

    @Field
    fun created(source: Product): OffsetDateTime = source.created

    @Field
    fun modified(source: Product): OffsetDateTime = source.modified
}
