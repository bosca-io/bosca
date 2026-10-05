package bosca.ecommerce.service

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.BuyOneGetOneRule
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.FreeShippingCartRule
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PercentOffCartRule
import bosca.ecommerce.model.Promotion
import bosca.ecommerce.model.PromotionRedemption
import bosca.ecommerce.model.PromotionType
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.Rule
import bosca.ecommerce.repository.PromotionRedemptionRepository
import bosca.ecommerce.repository.PromotionRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**promotion pricing — rule application + idempotency (mocked repos, no DB). */
@OptIn(ExperimentalUuidApi::class)
class PromotionPricerTest {

    private val promotionRepository = mockk<PromotionRepository>()
    private val redemptionRepository = mockk<PromotionRedemptionRepository>()
    private val pricer = PromotionPricer(promotionRepository, redemptionRepository)

    private val cartId = UUID.random()
    private val promotionId = UUID.random()
    private val storeId = UUID.random()

    private fun cart(vararg items: CartItem) = Cart(id = cartId, companyId = UUID.random(), storeId = storeId, items = items.toList(), expires = OffsetDateTime.now().plusSeconds(3600))

    private fun item(type: ProductType = ProductType.PHYSICAL, quantity: Int = 2, unit: String = "10.00") = CartItem(
        id = UUID.random(), catalogProductId = UUID.random(), type = type, quantity = quantity,
        retailPrice = Money.of(unit), salesPrice = Money.of(unit),
        retailSubtotal = Money.of(unit) * quantity, salesSubtotal = Money.of(unit) * quantity,
    )

    private fun stubRule(rule: Rule) {
        coEvery { redemptionRepository.getByCart(cartId) } returns listOf(PromotionRedemption(promotionId = promotionId, accountId = UUID.random(), cartId = cartId))
        coEvery { promotionRepository.get(promotionId) } returns Promotion(
            id = promotionId, storeId = storeId, code = "X", name = "X", type = PromotionType.CART, rule = rule,
            starts = OffsetDateTime.now().minusSeconds(60), ends = OffsetDateTime.now().plusSeconds(3600),
        )
    }

    @Test
    fun `no redemptions clears discounts`() = runTest {
        coEvery { redemptionRepository.getByCart(cartId) } returns emptyList()
        val result = pricer.price(cart(item().copy(discounts = Money.of("5.00"))))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `percent off discounts each line`() = runTest {
        stubRule(PercentOffCartRule(percent = 10.0)) // 10% of 20.00 = 2.00
        val result = pricer.price(cart(item()))
        assertEquals(Money.of("2.00"), result.items.first().discounts)
    }

    @Test
    fun `amount off spreads a fixed discount`() = runTest {
        stubRule(AmountOffCartRule(amount = Money.of("5.00")))
        val result = pricer.price(cart(item()))
        assertEquals(Money.of("5.00"), result.items.first().discounts)
    }

    @Test
    fun `buy one get one frees units at list price`() = runTest {
        stubRule(BuyOneGetOneRule(buyQuantity = 1, getQuantity = 1)) // qty 2 -> 1 free at 10.00
        val result = pricer.price(cart(item(quantity = 2)))
        assertEquals(Money.of("10.00"), result.items.first().discounts)
    }

    @Test
    fun `free shipping zeroes the shipping line only`() = runTest {
        stubRule(FreeShippingCartRule)
        val result = pricer.price(cart(item(type = ProductType.PHYSICAL), item(type = ProductType.SHIPPING, quantity = 1, unit = "7.50")))
        assertEquals(Money.ZERO, result.items.first { it.type == ProductType.PHYSICAL }.discounts)
        assertEquals(Money.of("7.50"), result.items.first { it.type == ProductType.SHIPPING }.discounts)
    }

    @Test
    fun `repricing is idempotent`() = runTest {
        stubRule(PercentOffCartRule(percent = 10.0))
        val once = pricer.price(cart(item()))
        val twice = pricer.price(once)
        assertEquals(once.items.first().discounts, twice.items.first().discounts)
    }

    /** Stub a single redemption pointing at a promotion with an explicit type/window/rule. */
    private fun stubPromotion(
        rule: Rule,
        type: PromotionType = PromotionType.CART,
        starts: OffsetDateTime = OffsetDateTime.now().minusSeconds(60),
        ends: OffsetDateTime = OffsetDateTime.now().plusSeconds(3600),
    ) {
        coEvery { redemptionRepository.getByCart(cartId) } returns listOf(PromotionRedemption(promotionId = promotionId, accountId = UUID.random(), cartId = cartId))
        coEvery { promotionRepository.get(promotionId) } returns Promotion(
            id = promotionId, storeId = storeId, code = "X", name = "X", type = type, rule = rule, starts = starts, ends = ends,
        )
    }

    @Test
    fun `a redemption whose promotion is missing is skipped`() = runTest {
        // mapNotNull drops the redemption when the promotion can't be loaded -> no discount applied.
        coEvery { redemptionRepository.getByCart(cartId) } returns listOf(PromotionRedemption(promotionId = promotionId, accountId = UUID.random(), cartId = cartId))
        coEvery { promotionRepository.get(promotionId) } returns null
        val result = pricer.price(cart(item()))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `a non-cart promotion does not discount the cart`() = runTest {
        // type != CART fails the filter, so the SUBSCRIPTION promotion never reaches the rule application.
        stubPromotion(PercentOffCartRule(percent = 50.0), type = PromotionType.SUBSCRIPTION)
        val result = pricer.price(cart(item()))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `a promotion that has not started yet does not apply`() = runTest {
        // starts in the future -> `!now.isBefore(starts)` is false -> filtered out.
        stubPromotion(PercentOffCartRule(percent = 50.0), starts = OffsetDateTime.now().plusSeconds(3600))
        val result = pricer.price(cart(item()))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `an expired promotion does not apply`() = runTest {
        // ends in the past -> `now.isBefore(ends)` is false -> filtered out.
        stubPromotion(PercentOffCartRule(percent = 50.0), ends = OffsetDateTime.now().minusSeconds(60))
        val result = pricer.price(cart(item()))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `a non-cart rule on a cart promotion is ignored`() = runTest {
        // filterIsInstance<CartRule> drops a SubscriptionRule even though the promotion type is CART.
        stubPromotion(bosca.ecommerce.model.PercentOffSubscriptionRule(percent = 50.0))
        val result = pricer.price(cart(item()))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `amount off exhausts on the first line and leaves later lines untouched`() = runTest {
        // 5.00 fixed: the first line (subtotal 20) absorbs all of it; the second gets nothing
        // (`if (!remaining.isPositive) return@map item`).
        stubPromotion(AmountOffCartRule(amount = Money.of("5.00")))
        val result = pricer.price(cart(item(), item()))
        assertEquals(Money.of("5.00"), result.items[0].discounts)
        assertEquals(Money.ZERO, result.items[1].discounts)
    }

    @Test
    fun `percent off is capped at a line's gross subtotal`() = runTest {
        // 150% of a 20.00 line would be 30.00, but `capped` clamps the discount to the 20.00 subtotal.
        stubPromotion(PercentOffCartRule(percent = 150.0))
        val result = pricer.price(cart(item()))
        assertEquals(Money.of("20.00"), result.items.first().discounts)
    }

    @Test
    fun `buy one get one frees nothing when the quantity is below the group size`() = runTest {
        // buy 2 get 1 -> group 3; a quantity-2 line yields 0 free units (`if (freeUnits <= 0) return@map item`).
        stubPromotion(BuyOneGetOneRule(buyQuantity = 2, getQuantity = 1))
        val result = pricer.price(cart(item(quantity = 2)))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }

    @Test
    fun `amount off skips a line with no room and spends on the next`() = runTest {
        // The first line has a zero gross subtotal, so its `room` is non-positive: the `let { if (it.isPositive) it else ZERO }`
        // else arm yields zero room, take is zero, and the whole fixed amount falls to the second line.
        stubPromotion(AmountOffCartRule(amount = Money.of("4.00")))
        val zeroLine = item(quantity = 1, unit = "0.00")
        val paidLine = item(quantity = 1, unit = "10.00")
        val result = pricer.price(cart(zeroLine, paidLine))
        assertEquals(Money.ZERO, result.items[0].discounts)
        assertEquals(Money.of("4.00"), result.items[1].discounts)
    }

    @Test
    fun `buy one get one with a non-positive group size is a no-op`() = runTest {
        // buyQuantity + getQuantity <= 0 -> `if (group <= 0) return items` returns the lines unchanged.
        stubPromotion(BuyOneGetOneRule(buyQuantity = 0, getQuantity = 0))
        val result = pricer.price(cart(item(quantity = 2)))
        assertEquals(Money.ZERO, result.items.first().discounts)
    }
}
