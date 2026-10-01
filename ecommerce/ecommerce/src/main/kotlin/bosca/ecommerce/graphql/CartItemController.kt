package bosca.ecommerce.graphql

import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartItemConfiguration
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.CatalogProduct
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Tax
import bosca.ecommerce.service.CatalogProductService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/**
 * Field wiring for the `CartItem` GraphQL type. Inventory reservations are an internal holding detail
 * and are deliberately NOT exposed.
 */
@TypeController
class CartItemController(
    private val catalogProductService: CatalogProductService,
) : GraphQLController<CartItem> {

    @Field
    fun id(source: CartItem): UUID = source.id

    @Field
    suspend fun catalogProduct(source: CartItem): CatalogProduct =
        catalogProductService.get(source.catalogProductId)
            ?: error("catalog product ${source.catalogProductId} not found")

    @Field
    fun type(source: CartItem): ProductType = source.type

    @Field
    fun quantity(source: CartItem): Int = source.quantity

    @Field
    fun status(source: CartItem): List<CartStatusFlag> = source.status.flags.toList()

    @Field
    fun baseRetailPrice(source: CartItem): Money = source.baseRetailPrice

    @Field
    fun retailPrice(source: CartItem): Money = source.retailPrice

    @Field
    fun salesPrice(source: CartItem): Money = source.salesPrice

    @Field
    fun retailSubtotal(source: CartItem): Money = source.retailSubtotal

    @Field
    fun salesSubtotal(source: CartItem): Money = source.salesSubtotal

    @Field
    fun discounts(source: CartItem): Money = source.discounts

    @Field
    fun paid(source: CartItem): Money = source.paid

    @Field
    fun taxes(source: CartItem): Tax = source.taxes

    @Field
    fun expires(source: CartItem): OffsetDateTime? = source.expires

    @Field
    fun parentId(source: CartItem): UUID? = source.parentId

    @Field
    fun configuration(source: CartItem): CartItemConfiguration = source.configuration
}
