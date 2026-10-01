package bosca.ecommerce.graphql

import bosca.ecommerce.model.FulfillmentCenter
import bosca.ecommerce.model.Inventory
import bosca.ecommerce.model.Product
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.ProductService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.UUID

/** Field wiring for the `Inventory` GraphQL type. `available` is the derived sellable count. */
@TypeController
class InventoryController(
    private val productService: ProductService,
    private val fulfillmentService: FulfillmentService,
) : GraphQLController<Inventory> {

    @Field
    fun id(source: Inventory): UUID = source.id

    @Field
    suspend fun product(source: Inventory): Product =
        productService.get(source.productId) ?: error("product ${source.productId} not found")

    @Field
    suspend fun fulfillmentCenter(source: Inventory): FulfillmentCenter =
        fulfillmentService.getCenter(source.fulfillmentCenterId)
            ?: error("fulfillment center ${source.fulfillmentCenterId} not found")

    @Field
    fun sku(source: Inventory): String = source.sku

    @Field
    fun quantity(source: Inventory): Int = source.quantity

    @Field
    fun pending(source: Inventory): Int = source.pending

    @Field
    fun inCart(source: Inventory): Int = source.inCart

    @Field
    fun available(source: Inventory): Int = source.available
}
