package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.PromotionAvailability
import bosca.serialization.UUID

/**
 * Redemption-limit counters. [redeem] is the concurrency guard: a single guarded UPDATE that bumps
 * `redeemed` only while it's below `quantity` and returns the row — a sold-out promotion returns null
 * (zero rows). The row lock serializes concurrent redemptions, so exactly `quantity` ever succeed.
 */
@Repository
interface PromotionAvailabilityRepository {

    @Query("select * from ecom.promotion_availability where promotion_id = :promotionId")
    suspend fun get(promotionId: UUID): PromotionAvailability?

    @Query("insert into ecom.promotion_availability (promotion_id, quantity, redeemed) values (:promotionId, :quantity, :redeemed) returning *")
    suspend fun add(availability: PromotionAvailability): PromotionAvailability

    @Query("update ecom.promotion_availability set redeemed = redeemed + 1 where promotion_id = :promotionId and redeemed < quantity returning *")
    suspend fun redeem(promotionId: UUID): PromotionAvailability?

    @Query("update ecom.promotion_availability set redeemed = greatest(redeemed - 1, 0) where promotion_id = :promotionId returning *")
    suspend fun release(promotionId: UUID): PromotionAvailability?
}
