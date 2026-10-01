package bosca.ecommerce.service

import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Tax

/**
 * Default state-rate tax calculator: applies a per-state percentage to the taxable amount as the
 * `state` tax component. [ratesByState] maps a state/province code to its percentage (e.g.
 * `"CA" -> 8.25`); an unconfigured state or absent address yields no tax. Real jurisdiction services
 * (Avalara, TaxJar) are a follow-up — they implement [TaxCalculator] and replace this provider.
 */
class DefaultTaxCalculator(
    private val ratesByState: Map<String, Double>,
) : TaxCalculator {

    override suspend fun calculate(taxableAmount: Money, address: CartAddress?): Tax {
        if (address == null) return Tax.ZERO
        val rate = ratesByState[address.state] ?: return Tax.ZERO
        if (rate <= 0.0) return Tax.ZERO
        return Tax.of(state = taxableAmount.percentage(rate.toBigDecimal()))
    }
}
