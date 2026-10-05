package bosca.ecommerce.model

import bosca.db.annotation.DbMapper
import bosca.db.mapper.EnumMapper
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Opens a cart in a store. The buyer is identified by account and/or customer (both optional). */
@Serializable
data class CartInput(
    @Contextual
    val storeId: UUID,
    @Contextual
    val accountId: UUID? = null,
    @Contextual
    val customerId: UUID? = null,
)

/** Adds a line to a cart. [configuration] carries line-type-specific data (e.g. a subscription plan). */
@Serializable
data class AddCartItemInput(
    @Contextual
    val catalogProductId: UUID,
    val quantity: Int,
    val configuration: CartItemConfiguration? = null,
    @Contextual
    val parentId: UUID? = null,
)

/**
 * Payment supplied at cart submission. [token] is a single-use client-side payment token (never a
 * PAN); [save] stores a reusable method for later renewals. Omit to submit unpaid (PAYMENT_DUE).
 */
@Serializable
data class SubmitPaymentInput(
    /** Amount this payment covers; null = the full remaining due. Enables split tender (≤ due each). */
    val amount: Money? = null,
    val token: String? = null,
    val type: PaymentType = PaymentType.CREDIT_CARD,
    val save: Boolean = false,
    val email: String? = null,
    /** Raw card for PAN-processing gateways (e.g. BluePay) when no [token] is supplied. Never persisted. */
    val creditCard: CreditCard? = null,
    /** The check number, for [PaymentType.CHECK]. */
    val checkNumber: String? = null,
    /** The redeemable company-credit number, for [PaymentType.COMPANY_CREDIT]. */
    val companyCreditNumber: String? = null,
)

/**
 * Where a refund is returned, mirroring the legacy refund tenders: [ORIGINAL] reverses the cart's
 * original payment(s) on the gateway, [ACCOUNT_CREDIT] issues store credit to the account, [CHECK]
 * records a refund-by-check.
 */
@Serializable
@DbMapper(RefundTenderMapper::class)
enum class RefundTender { ORIGINAL, ACCOUNT_CREDIT, CHECK }

/** Bridges [RefundTender] to the native `ecom.refund_tender` enum (lowercase labels). */
object RefundTenderMapper : EnumMapper<RefundTender>({ RefundTender.valueOf(it.uppercase()) })

/**
 * One line in a batch item refund: refund [quantity] units of cart item [itemId]. Partial quantities
 * are allowed (e.g. 1 unit of a quantity-2 line); the line is reduced (or removed when fully
 * refunded) and its inventory restocked, exactly as legacy `removeItems` did.
 */
@Serializable
data class RefundItemInput(
    @Contextual
    val itemId: UUID,
    val quantity: Int,
)

/** Sets (upserts) a cart's billing or shipping address. */
@Serializable
data class SetCartAddressInput(
    val type: AddressType,
    val firstName: String,
    val lastName: String,
    val address1: String,
    val address2: String? = null,
    val city: String,
    val state: String,
    val country: String,
    val zip: String,
    val phone: String,
    val email: String? = null,
    val note: String? = null,
)
