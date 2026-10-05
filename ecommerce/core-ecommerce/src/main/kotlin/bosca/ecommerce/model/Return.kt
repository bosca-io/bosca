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
 * A return / RMA against a paid order (`ecom.returns`; the cart IS the order, so [cartId] is the order
 * key). A customer-or-admin requests it, an admin approves it, the goods come back ([ReturnStatus.RECEIVED]),
 * then it is refunded ([ReturnStatus.REFUNDED]) — restock + money reuse the cart's `refundItems` mechanic.
 * Models the workflow the legacy never had; the money/inventory effects are the existing item-refund path.
 *
 * No `version` column (transitions take row locks like the rest of the module); bare timestamp names.
 */
@BatchKey("id")
@Serializable
data class Return(
    @Contextual
    val id: UUID = UUID.NIL,
    @Contextual
    @ColumnName("cart_id")
    val cartId: UUID,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    @Contextual
    @ColumnName("company_id")
    val companyId: UUID,
    val status: ReturnStatus = ReturnStatus.REQUESTED,
    /** Why the buyer is returning (free text), and on rejection the admin's reason. */
    val reason: String? = null,
    /** How the eventual refund is issued (original method / store credit / check). */
    val tender: RefundTender = RefundTender.ORIGINAL,
    /** The order lines (and quantities) being returned. */
    @property:DbMapper(JsonbMapper::class)
    val lines: List<ReturnLine> = emptyList(),
    /** The amount refunded once the return reaches REFUNDED (zero before that). */
    @ColumnName("refunded_amount")
    val refundedAmount: Money = Money.ZERO,
    /** A check number when the refund [tender] is CHECK. */
    @ColumnName("check_number")
    val checkNumber: String? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val deleted: OffsetDateTime? = null,
)

/**
 * One line of a [Return]: the cart [itemId] being returned (the key `refundItems` reduces + restocks)
 * and the [quantity]. [catalogProductId] is denormalized from the order line for display.
 */
@Serializable
data class ReturnLine(
    @Contextual
    val itemId: UUID,
    @Contextual
    val catalogProductId: UUID,
    val quantity: Int,
)
