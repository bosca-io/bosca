package bosca.ecommerce.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The sealed promotion rule hierarchy stored as `jsonb` in `ecom.promotions.rule`. A rule is either
 * a [CartRule] (evaluated during cart pricing) or a [SubscriptionRule] (evaluated
 * during renewal pricing). Rule data here is the discount *specification*; the evaluation
 * logic lands with the promotions engine. Rules are pure data — no I/O — so they unit-test
 * cleanly.
 */
@Serializable
sealed interface Rule

/** A rule that adjusts cart item pricing. */
@Serializable
sealed interface CartRule : Rule

/** A rule that adjusts subscription renewal pricing. */
@Serializable
sealed interface SubscriptionRule : Rule

/** Take a percentage off the affected cart amount (e.g. `percent = 10.0` is 10% off). */
@Serializable
@SerialName("cartPercentOff")
data class PercentOffCartRule(val percent: Double) : CartRule

/** Take a fixed amount off the cart. */
@Serializable
@SerialName("cartAmountOff")
data class AmountOffCartRule(val amount: Money) : CartRule

/** Zero the cart's shipping line(s). */
@Serializable
@SerialName("cartFreeShipping")
data object FreeShippingCartRule : CartRule

/**
 * Buy-N-get-M: per line, for every ([buyQuantity] + [getQuantity]) units, [getQuantity] are free
 * (discounted at list price). An item-based rule alongside the amount-based ones.
 */
@Serializable
@SerialName("cartBuyOneGetOne")
data class BuyOneGetOneRule(
    val buyQuantity: Int = 1,
    val getQuantity: Int = 1,
) : CartRule

/** Take a percentage off the subscription renewal price. */
@Serializable
@SerialName("subscriptionPercentOff")
data class PercentOffSubscriptionRule(val percent: Double) : SubscriptionRule

/** Take a fixed amount off the subscription renewal price. */
@Serializable
@SerialName("subscriptionAmountOff")
data class AmountOffSubscriptionRule(val amount: Money) : SubscriptionRule
