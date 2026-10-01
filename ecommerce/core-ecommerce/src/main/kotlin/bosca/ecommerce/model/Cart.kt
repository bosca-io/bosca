package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A shopping cart — the selling document everything hangs off. The cart is loaded, mutated, and saved
 * as a single row: [items] is the embedded jsonb line array (strongly typed via
 * `@property:DbMapper(JsonbMapper::class)`), and the `*Total`/`*Subtotal` Money columns are
 * denormalized totals recomputed on every mutation. [status] is the [CartStatusFlag] bitmask. The
 * cart-expiration sweep reclaims rows whose [expires] has passed while still OPEN.
 */
@BatchKey("id")
@Serializable
data class Cart(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID? = null,
    @Contextual
    @ColumnName("customer_id")
    val customerId: UUID? = null,
    val status: CartStatus = CartStatus.OPEN,
    @property:DbMapper(JsonbMapper::class)
    val items: List<CartItem> = emptyList(),
    @property:DbMapper(JsonbMapper::class)
    val extras: CartExtras = EmptyCartExtras,
    @Contextual
    val expires: OffsetDateTime,
    @ColumnName("billing_same_as_shipping")
    val billingSameAsShipping: Boolean = false,
    @ColumnName("retail_total")
    val retailTotal: Money = Money.ZERO,
    @ColumnName("retail_subtotal")
    val retailSubtotal: Money = Money.ZERO,
    @ColumnName("sales_total")
    val salesTotal: Money = Money.ZERO,
    @ColumnName("sales_subtotal")
    val salesSubtotal: Money = Money.ZERO,
    val shipping: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val discounts: Money = Money.ZERO,
    val paid: Money = Money.ZERO,
    @ColumnName("pending_paid")
    val pendingPaid: Money = Money.ZERO,
    val due: Money = Money.ZERO,
    @ColumnName("refund_due")
    val refundDue: Money = Money.ZERO,
    val quantity: Int = 0,
    /** The ISO-4217 currency of this cart's amounts (stamped from the store at creation). */
    val currency: String = "USD",
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)
