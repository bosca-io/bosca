package bosca.ecommerce.service

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.BuyOneGetOneRule
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.CartRule
import bosca.ecommerce.model.FreeShippingCartRule
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PercentOffCartRule
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.PromotionType
import bosca.ecommerce.repository.PromotionRedemptionRepository
import bosca.ecommerce.repository.PromotionRepository
import bosca.serialization.OffsetDateTime

/**
 * Applies a cart's active promotions during pricing. The cart's applied promotions are its
 * `promotion_redemptions` rows. Idempotent: every reprice resets line discounts to zero and
 * re-applies, so it never double-discounts. Runs before tax — discounts reduce the taxable
 * amount. Discounts sit in `CartItem.discounts` (line `salesSubtotal` stays gross); the cart's
 * recompute nets them into `salesTotal`.
 */
class PromotionPricer(
    private val promotionRepository: PromotionRepository,
    private val redemptionRepository: PromotionRedemptionRepository,
) : CartPricer {

    override val order: Int = 10

    override suspend fun price(cart: Cart): Cart {
        val base = cart.items.map { it.copy(discounts = Money.ZERO) }
        val redemptions = redemptionRepository.getByCart(cart.id)
        if (redemptions.isEmpty()) return cart.copy(items = base)

        val now = OffsetDateTime.now()
        val rules = redemptions
            .mapNotNull { promotionRepository.get(it.promotionId) }
            .filter { it.type == PromotionType.CART && !now.isBefore(it.starts) && now.isBefore(it.ends) }
            .map { it.rule }
            .filterIsInstance<CartRule>()

        var items = base
        for (rule in rules) {
            items = when (rule) {
                is PercentOffCartRule -> items.map { item ->
                    item.copy(discounts = capped(item, item.discounts + item.salesSubtotal.percentage(rule.percent.toBigDecimal())))
                }
                is AmountOffCartRule -> spreadFixed(rule.amount, items)
                is FreeShippingCartRule -> items.map { item ->
                    if (item.type == ProductType.SHIPPING) item.copy(discounts = item.salesSubtotal) else item
                }
                is BuyOneGetOneRule -> applyBogo(rule, items)
            }
        }
        return cart.copy(items = items)
    }

    /** Spread a fixed discount greedily across lines, never exceeding a line's remaining room. */
    private fun spreadFixed(amount: Money, items: List<CartItem>): List<CartItem> {
        var remaining = amount
        return items.map { item ->
            if (!remaining.isPositive) return@map item
            val room = (item.salesSubtotal - item.discounts).let { if (it.isPositive) it else Money.ZERO }
            val take = minOf(remaining, room)
            remaining -= take
            item.copy(discounts = item.discounts + take)
        }
    }

    /** Per line: for every (buy+get) units, [BuyOneGetOneRule.getQuantity] are free (at list price). */
    private fun applyBogo(rule: BuyOneGetOneRule, items: List<CartItem>): List<CartItem> {
        val group = rule.buyQuantity + rule.getQuantity
        if (group <= 0) return items
        return items.map { item ->
            val freeUnits = (item.quantity / group) * rule.getQuantity
            if (freeUnits <= 0) return@map item
            item.copy(discounts = capped(item, item.discounts + item.retailPrice * freeUnits))
        }
    }

    /** A line's discount never exceeds its gross subtotal (no negative prices). */
    private fun capped(item: CartItem, discount: Money): Money = minOf(discount, item.salesSubtotal)
}
