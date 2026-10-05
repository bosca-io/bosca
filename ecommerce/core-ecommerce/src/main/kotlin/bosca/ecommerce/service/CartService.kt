package bosca.ecommerce.service

import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AddCartItemInput
import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.CartInput
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.CartSubmitResult
import bosca.ecommerce.model.Company
import bosca.ecommerce.model.Customer
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundItemInput
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.SetCartAddressInput
import bosca.ecommerce.model.ShippingRate
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.SubmitPaymentInput
import bosca.graphql.Batch
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Carts and their line items. Item mutations reserve/release inventory holds atomically with
 * the cart write (a single transaction; nested reservations are savepoints, so a failure rolls back
 * the whole change). Totals are recomputed on every mutation. Submission (`cartSubmit`) lands in the
 * next step alongside payment + subscription creation.
 */
interface CartService : Service {

    /** A cart by id. */
    suspend fun get(id: UUID): Cart?

    /** Carts by id (batch). */
    suspend fun getByIds(ids: List<UUID>): List<Cart>

    /** DataLoader batch resolver for `Cart.company`, keyed by cart id — loads each cart's company in one batched query. */
    suspend fun addCompaniesToBatch(batch: Batch<UUID, Company>)

    /** DataLoader batch resolver for `Cart.store`, keyed by cart id — loads each cart's store in one batched query. */
    suspend fun addStoresToBatch(batch: Batch<UUID, Store>)

    /** DataLoader batch resolver for `Cart.account`, keyed by cart id — loads each cart's account (if any) in one batched query. */
    suspend fun addAccountsToBatch(batch: Batch<UUID, Account>)

    /** DataLoader batch resolver for `Cart.customer`, keyed by cart id — loads each cart's customer (if any) in one batched query. */
    suspend fun addCustomersToBatch(batch: Batch<UUID, Customer>)

    /** Carts in a store, newest first (admin inspector). */
    suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Cart>

    /** Carts in a store whose status has [status] set, newest first — the status-filtered admin list (legacy `getCarts(companyId, status)`). */
    suspend fun getByStoreAndStatus(storeId: UUID, status: CartStatusFlag, offset: Int, limit: Int): List<Cart>

    /** Paid carts ("orders") for a store, newest first — the fulfillment/orders admin view. */
    suspend fun getOrdersByStore(storeId: UUID, offset: Int, limit: Int): List<Cart>

    /** Open a new cart; [expires] is derived from the store's cart-expiration policy. */
    suspend fun create(input: CartInput, principalId: UUID?): Cart

    /** Add a line, reserving inventory if the product is stocked. Recomputes totals. */
    suspend fun addItem(cartId: UUID, input: AddCartItemInput, principalId: UUID?): Cart

    /** Change a line's quantity, re-reserving inventory to match. Recomputes totals. */
    suspend fun updateItemQuantity(cartId: UUID, itemId: UUID, quantity: Int, principalId: UUID?): Cart

    /** Remove a line, releasing its inventory holds. Recomputes totals. */
    suspend fun removeItem(cartId: UUID, itemId: UUID, principalId: UUID?): Cart

    /**
     * Submit the cart for checkout: commit its inventory holds (in_cart -> pending) and move from
     * OPEN to PENDING. When [payment] is supplied, charges the cart's due — success -> PAID, failure
     * -> PAYMENT_DUE + PAYMENT_FAILED; omitted -> PAYMENT_DUE (pay later). Returns the cart + any
     * payments. Subscription creation plugs in here. Fails on an empty or non-open cart.
     */
    suspend fun submit(cartId: UUID, payment: SubmitPaymentInput?, principalId: UUID?): CartSubmitResult

    /** Set (upsert) the cart's billing or shipping address. */
    suspend fun setAddress(cartId: UUID, input: SetCartAddressInput, principalId: UUID?): CartAddress

    /** Remove the cart's address of [type] (legacy `removeAddress`). Returns the cart. */
    suspend fun removeAddress(cartId: UUID, type: AddressType, principalId: UUID?): Cart

    /** Set whether billing mirrors the shipping address (drops any separate billing address when true). */
    suspend fun setBillingSameAsShipping(cartId: UUID, value: Boolean, principalId: UUID?): Cart

    /**
     * Re-run the cart pricing pipeline (promotions, tax, shipping) and persist the refreshed totals.
     * Used by out-of-band changes that affect pricing — e.g. applying a promotion code.
     */
    suspend fun reprice(cartId: UUID): Cart

    /** The cart's addresses (billing/shipping). */
    suspend fun getAddresses(cartId: UUID): List<CartAddress>

    /**
     * Set the cart's SHIPPING line to a quoted rate (replacing any existing one): a line for the
     * store's shipping catalog product priced at [rate], carrying the offered [options] in its
     * configuration. Reprices.
     */
    suspend fun setShipping(cartId: UUID, rate: ShippingRate, options: List<ShippingRate>, principalId: UUID?): Cart

    /**
     * Reclaim ALL expired OPEN carts (the runner sweep): release each line's inventory holds and flip
     * the cart to CANCELLED + EXPIRED. Batches internally until none remain (expiring a cart removes
     * it from the due set), so no expired cart is left behind. Returns how many were expired.
     */
    suspend fun expireCarts(): Int

    /**
     * Recompute a paid order's fulfillment status from its shipments: once any shipment
     * has shipped the cart is `SHIPPING`; once every shipment has shipped it is `SHIPPED` + `COMPLETE`
     * (emitting `CartCompleted`). Idempotent — a `COMPLETE` cart is left untouched. Called after a
     * shipment is shipped.
     */
    suspend fun advanceFulfillment(cartId: UUID, principalId: UUID?)

    /**
     * Mark a paid order COMPLETE — an admin override that finalizes an order the normal fulfillment
     * path can't progress (e.g. an all-digital order, or one fulfilled outside the system). Clears the
     * in-progress flags (PENDING/PREPARING/SHIPPING), sets COMPLETE, emits `CartCompleted`. Audited.
     * Mirrors legacy `setCartComplete`.
     */
    suspend fun complete(cartId: UUID, principalId: UUID?): Cart

    /**
     * Mark an order CANCELLED — an admin status override (mirrors legacy `setCartCancelled`). A status
     * change only; any money already taken is returned separately via [refundItems] / payment refunds.
     * Audited.
     */
    suspend fun cancel(cartId: UUID, principalId: UUID?): Cart

    /**
     * Refund one or more (partially) line items on a paid order, mirroring legacy `removeItems` +
     * `refund`: each [items] line is reduced by its quantity (the line is removed when fully refunded)
     * and its inventory restocked, the cart is repriced so the price drop surfaces as `refundDue`, and
     * that amount is refunded against the cart's payment(s) to [tender] (with [checkNumber] for a CHECK
     * refund) while `paid` is reduced to match. Returns the updated cart. Audited.
     */
    suspend fun refundItems(
        cartId: UUID,
        items: List<RefundItemInput>,
        tender: RefundTender,
        checkNumber: String?,
        reason: String?,
        principalId: UUID?,
    ): Cart

    /**
     * Confirm a CHECK payment on the cart has cleared (legacy `confirm`): marks the payment confirmed
     * and settles its amount from the cart's `pendingPaid` into `paid`, then reprices. Audited.
     */
    suspend fun confirmCheck(cartId: UUID, paymentId: UUID, checkNumber: String?, principalId: UUID?): Cart

    /** Lock or unlock a cart against further buyer edits (legacy `setCartLocked`). Audited. */
    suspend fun setLocked(cartId: UUID, locked: Boolean, principalId: UUID?): Cart

    /**
     * Admin override of a line's unit retail/sales price after submission (legacy `setCartItemPrice`),
     * for post-sale corrections (price match, damage discount). Reprices, surfacing any resulting
     * `refundDue`/`due`. Audited.
     */
    suspend fun setItemPrice(cartId: UUID, itemId: UUID, retailPrice: Money, salesPrice: Money, principalId: UUID?): Cart
}
