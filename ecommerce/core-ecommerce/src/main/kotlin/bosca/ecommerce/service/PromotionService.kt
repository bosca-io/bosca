package bosca.ecommerce.service

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionInput
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Promotions: admin CRUD plus cart code application. Application atomically reserves a redemption
 * against the promotion's availability (so a limited coupon can't over-redeem concurrently), records
 * it, and reprices the cart. The cart's applied promotions are its `promotion_redemptions` rows.
 */
interface PromotionService : Service {

    /** A promotion by id. */
    suspend fun get(id: UUID): Promotion?

    /** A promotion by its store + code (active or not). */
    suspend fun getByCode(storeId: UUID, code: String): Promotion?

    /** A page of promotions for a store, newest first. */
    suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Promotion>

    /** Create a promotion (and its availability cap if [PromotionInput.quantity] is set). */
    suspend fun create(input: PromotionInput, principalId: UUID?): Promotion

    /** Edit a promotion's definition. */
    suspend fun edit(id: UUID, input: PromotionInput, principalId: UUID?): Promotion

    /** Soft-delete a promotion. */
    suspend fun delete(id: UUID, principalId: UUID?)

    /**
     * Apply a code to a cart: validate the active window, atomically reserve a redemption against the
     * cart's account, append the redemption, and reprice. Requires the cart to have an account.
     */
    suspend fun applyToCart(cartId: UUID, code: String, principalId: UUID?): Cart

    /** Remove a previously-applied code from a cart: release its redemption and reprice. */
    suspend fun removeFromCart(cartId: UUID, code: String, principalId: UUID?): Cart
}
