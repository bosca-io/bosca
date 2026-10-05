package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.events.PromotionRedeemed
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionAvailability
import bosca.ecommerce.model.PromotionInput
import bosca.ecommerce.model.PromotionRedemption
import bosca.ecommerce.repository.PromotionAvailabilityRepository
import bosca.ecommerce.repository.PromotionRedemptionRepository
import bosca.ecommerce.repository.PromotionRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Promotions. Application reserves a redemption against the promotion's availability with the atomic
 * guard in [PromotionAvailabilityRepository.redeem] (so a limited coupon can't over-redeem under
 * concurrency — the same row-lock guarantee as inventory), records the redemption, and reprices the
 * cart through [CartService.reprice] (which runs the [bosca.ecommerce.service.PromotionPricer]).
 */
@ServiceImplementation
class PromotionServiceImpl(
    private val promotionRepository: PromotionRepository,
    private val availabilityRepository: PromotionAvailabilityRepository,
    private val redemptionRepository: PromotionRedemptionRepository,
    private val cartService: CartService,
    private val auditService: EcomAuditService,
) : PromotionService {

    override suspend fun get(id: UUID): Promotion? = promotionRepository.get(id)

    override suspend fun getByCode(storeId: UUID, code: String): Promotion? = promotionRepository.getByCode(storeId, code)

    override suspend fun getByStore(storeId: UUID, offset: Int, limit: Int): List<Promotion> =
        promotionRepository.getByStore(storeId, offset, limit)

    override suspend fun create(input: PromotionInput, principalId: UUID?): Promotion = transaction {
        val promotion = promotionRepository.add(
            Promotion(
                storeId = input.storeId, code = input.code, name = input.name, type = input.type,
                rule = input.rule, starts = input.starts, ends = input.ends,
                perAccountLimit = input.perAccountLimit, frequencyLimit = input.frequencyLimit,
            ),
        )
        input.quantity?.let { availabilityRepository.add(PromotionAvailability(promotionId = promotion.id, quantity = it)) }
        audit(promotion, "created", before = null, principalId = principalId)
        promotion
    }

    override suspend fun edit(id: UUID, input: PromotionInput, principalId: UUID?): Promotion = transaction {
        val existing = promotionRepository.get(id) ?: error("promotion $id not found")
        val updated = promotionRepository.update(
            existing.copy(
                code = input.code, name = input.name, type = input.type, rule = input.rule,
                starts = input.starts, ends = input.ends,
                perAccountLimit = input.perAccountLimit, frequencyLimit = input.frequencyLimit,
            ),
        ) ?: error("promotion $id not found")
        audit(updated, "updated", before = existing, principalId = principalId)
        updated
    }

    override suspend fun delete(id: UUID, principalId: UUID?): Unit = transaction {
        val existing = promotionRepository.get(id) ?: error("promotion $id not found")
        promotionRepository.softDelete(id)
        audit(existing, "deleted", before = existing, principalId = principalId)
    }

    override suspend fun applyToCart(cartId: UUID, code: String, principalId: UUID?): Cart = transaction {
        val cart = cartService.get(cartId) ?: error("cart $cartId not found")
        val accountId = cart.accountId ?: error("cart $cartId has no account to redeem a promotion against")
        val promotion = promotionRepository.getByCode(cart.storeId, code) ?: error("promotion $code not found")
        val now = OffsetDateTime.now()
        check(!now.isBefore(promotion.starts) && now.isBefore(promotion.ends)) { "promotion $code is not active" }
        if (promotion.perAccountLimit > 0) {
            val redeemed = redemptionRepository.getByPromotionAndAccountSince(promotion.id, accountId, promotion.frequencyLimit.windowStart(now)).size
            check(redeemed < promotion.perAccountLimit) {
                "promotion $code already redeemed ${promotion.perAccountLimit} time(s) by this account in the ${promotion.frequencyLimit} window"
            }
        }
        // Reserve against the availability cap (if any). The guarded update returns null when sold out.
        if (availabilityRepository.get(promotion.id) != null) {
            availabilityRepository.redeem(promotion.id) ?: error("promotion $code is fully redeemed")
        }
        redemptionRepository.add(PromotionRedemption(promotionId = promotion.id, accountId = accountId, cartId = cartId))
        audit(promotion, "applied", before = null, principalId = principalId)
        PromotionRedeemed(
            promotionId = promotion.id, storeId = promotion.storeId, accountId = accountId, code = promotion.code, cartId = cartId,
        ).dispatch()
        cartService.reprice(cartId)
    }

    override suspend fun removeFromCart(cartId: UUID, code: String, principalId: UUID?): Cart = transaction {
        val cart = cartService.get(cartId) ?: error("cart $cartId not found")
        val promotion = promotionRepository.getByCode(cart.storeId, code) ?: error("promotion $code not found")
        redemptionRepository.deleteByCartAndPromotion(cartId, promotion.id)
        if (availabilityRepository.get(promotion.id) != null) availabilityRepository.release(promotion.id)
        audit(promotion, "removed", before = null, principalId = principalId)
        cartService.reprice(cartId)
    }

    private suspend fun audit(promotion: Promotion, action: String, before: Promotion?, principalId: UUID?) {
        auditService.record(
            entityType = "promotion",
            entityId = promotion.id,
            action = action,
            serializer = Promotion.serializer(),
            before = before,
            after = promotion,
            principalId = principalId,
            storeId = promotion.storeId,
        )
    }
}
