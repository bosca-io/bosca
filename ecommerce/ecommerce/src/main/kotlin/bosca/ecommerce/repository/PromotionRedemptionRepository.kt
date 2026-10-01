package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.PromotionRedemption
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Append-only redemption audit; a cart's applied promotions are its rows here. */
@Repository
interface PromotionRedemptionRepository {

    @Query("insert into ecom.promotion_redemptions (promotion_id, account_id, cart_id) values (:promotionId, :accountId, :cartId) returning *")
    suspend fun add(redemption: PromotionRedemption): PromotionRedemption

    @Query("select * from ecom.promotion_redemptions where cart_id = :cartId")
    suspend fun getByCart(cartId: UUID): List<PromotionRedemption>

    @Query("delete from ecom.promotion_redemptions where cart_id = :cartId and promotion_id = :promotionId")
    suspend fun deleteByCartAndPromotion(cartId: UUID, promotionId: UUID)

    /** An account's redemptions of a promotion since [since] — backs the per-account limit guard. */
    @Query("select * from ecom.promotion_redemptions where promotion_id = :promotionId and account_id = :accountId and created >= :since")
    suspend fun getByPromotionAndAccountSince(promotionId: UUID, accountId: UUID, since: OffsetDateTime): List<PromotionRedemption>
}
