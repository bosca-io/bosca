package bosca.ecommerce.service

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.Money
import bosca.ecommerce.repository.CartAddressRepository

/**
 * Computes per-line tax during cart pricing. Runs AFTER promotions (order 20 > the promotion
 * pricer's 10) so tax applies to the discounted amount. Reads the cart's SHIPPING address (the tax
 * jurisdiction; falls back to BILLING), so changing an address reprices tax — the address →
 * recalc seam goes live here with no extra wiring. Idempotent: `taxes` is fully recomputed each pass.
 */
class TaxPricer(
    private val taxCalculator: TaxCalculator,
    private val cartAddressRepository: CartAddressRepository,
) : CartPricer {

    override val order: Int = 20

    override suspend fun price(cart: Cart): Cart {
        val addresses = cartAddressRepository.getByCart(cart.id)
        val address = addresses.firstOrNull { it.type == AddressType.SHIPPING }
            ?: addresses.firstOrNull { it.type == AddressType.BILLING }
        val items = cart.items.map { item ->
            val taxable = (item.salesSubtotal - item.discounts).let { if (it.isPositive) it else Money.ZERO }
            item.copy(taxes = taxCalculator.calculate(taxable, address))
        }
        return cart.copy(items = items)
    }
}
