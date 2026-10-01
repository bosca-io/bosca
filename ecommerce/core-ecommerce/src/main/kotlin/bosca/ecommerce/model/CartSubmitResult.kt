package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/**
 * The outcome of submitting a cart: the submitted [cart], the [payments] taken at checkout (empty
 * when paying later), and any [subscriptions] created from SUBSCRIPTION lines.
 */
@Serializable
data class CartSubmitResult(
    val cart: Cart,
    val payments: List<Payment> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
)
