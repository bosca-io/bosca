package bosca.ecommerce.graphql

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.Store
import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.PaymentService
import bosca.graphql.Batch
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Field wiring for the `Cart` GraphQL type. Status is exposed as the decoded flag set. */
@TypeController
class CartController(
    private val cartService: CartService,
    private val paymentService: PaymentService,
) : GraphQLController<Cart> {

    @Field
    fun id(source: Cart): UUID = source.id

    // Batched via the DataLoader: keyed by `Cart.id`, the resolver runs ONCE per request with all the cart
    // ids needing a `company` so a multi-cart listing costs O(1) batched loads. The batch + cache live in
    // the service; the resolver just delegates.
    @Field
    suspend fun company(batch: Batch<UUID, Company>) = cartService.addCompaniesToBatch(batch)

    @Field
    suspend fun store(batch: Batch<UUID, Store>) = cartService.addStoresToBatch(batch)

    @Field
    suspend fun account(batch: Batch<UUID, Account>) = cartService.addAccountsToBatch(batch)

    @Field
    suspend fun customer(batch: Batch<UUID, Customer>) = cartService.addCustomersToBatch(batch)

    @Field
    fun status(source: Cart): List<CartStatusFlag> = source.status.flags.toList()

    @Field
    fun items(source: Cart): List<CartItem> = source.items

    @Field
    suspend fun addresses(source: Cart): List<CartAddress> = cartService.getAddresses(source.id)

    @Field
    fun billingSameAsShipping(source: Cart): Boolean = source.billingSameAsShipping

    @Field
    suspend fun payments(source: Cart, offset: Int, limit: Int): List<Payment> = paymentService.getByCart(source.id, offset, limit)

    @Field
    fun retailTotal(source: Cart): Money = source.retailTotal

    @Field
    fun retailSubtotal(source: Cart): Money = source.retailSubtotal

    @Field
    fun salesTotal(source: Cart): Money = source.salesTotal

    @Field
    fun salesSubtotal(source: Cart): Money = source.salesSubtotal

    @Field
    fun shipping(source: Cart): Money = source.shipping

    @Field
    fun tax(source: Cart): Money = source.tax

    @Field
    fun discounts(source: Cart): Money = source.discounts

    @Field
    fun paid(source: Cart): Money = source.paid

    @Field
    fun pendingPaid(source: Cart): Money = source.pendingPaid

    @Field
    fun refundDue(source: Cart): Money = source.refundDue

    @Field
    fun due(source: Cart): Money = source.due

    @Field
    fun currency(source: Cart): String = source.currency

    @Field
    fun quantity(source: Cart): Int = source.quantity

    @Field
    fun expires(source: Cart): OffsetDateTime = source.expires

    @Field
    fun created(source: Cart): OffsetDateTime = source.created

    @Field
    fun modified(source: Cart): OffsetDateTime = source.modified
}
