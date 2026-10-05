package bosca.ecommerce.graphql

import bosca.ecommerce.model.AmountOffCartRule
import bosca.ecommerce.model.AmountOffSubscriptionRule
import bosca.ecommerce.model.BuyOneGetOneRule
import bosca.ecommerce.model.FreeShippingCartRule
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PercentOffCartRule
import bosca.ecommerce.model.PercentOffSubscriptionRule
import kotlin.test.Test
import kotlin.test.assertEquals

/** Field wiring for the polymorphic `Rule` union members: each exposes its discriminator + payload. */
class RuleControllersTest {

    @Test
    fun `percent off cart rule exposes its discriminator and percent`() {
        val rule = PercentOffCartRule(percent = 10.0)
        val controller = PercentOffCartRuleController()
        assertEquals("cartPercentOff", controller.type(rule))
        assertEquals(10.0, controller.percent(rule))
    }

    @Test
    fun `amount off cart rule exposes its discriminator and amount`() {
        val rule = AmountOffCartRule(amount = Money.of("5.00"))
        val controller = AmountOffCartRuleController()
        assertEquals("cartAmountOff", controller.type(rule))
        assertEquals(Money.of("5.00"), controller.amount(rule))
    }

    @Test
    fun `free shipping cart rule exposes its discriminator`() {
        val controller = FreeShippingCartRuleController()
        assertEquals("cartFreeShipping", controller.type(FreeShippingCartRule))
    }

    @Test
    fun `buy one get one rule exposes its discriminator and quantities`() {
        val rule = BuyOneGetOneRule(buyQuantity = 2, getQuantity = 1)
        val controller = BuyOneGetOneRuleController()
        assertEquals("cartBuyOneGetOne", controller.type(rule))
        assertEquals(2, controller.buyQuantity(rule))
        assertEquals(1, controller.getQuantity(rule))
    }

    @Test
    fun `percent off subscription rule exposes its discriminator and percent`() {
        val rule = PercentOffSubscriptionRule(percent = 15.0)
        val controller = PercentOffSubscriptionRuleController()
        assertEquals("subscriptionPercentOff", controller.type(rule))
        assertEquals(15.0, controller.percent(rule))
    }

    @Test
    fun `amount off subscription rule exposes its discriminator and amount`() {
        val rule = AmountOffSubscriptionRule(amount = Money.of("3.00"))
        val controller = AmountOffSubscriptionRuleController()
        assertEquals("subscriptionAmountOff", controller.type(rule))
        assertEquals(Money.of("3.00"), controller.amount(rule))
    }
}
