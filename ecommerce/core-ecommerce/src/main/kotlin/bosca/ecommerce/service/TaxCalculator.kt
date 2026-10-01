package bosca.ecommerce.service

import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Tax

/**
 * Computes the [Tax] breakdown for a taxable amount given the destination address. A single
 * DI-registered implementation backs the cart's tax pricing; the default is a state-rate calculator,
 * and a real provider (e.g. Avalara) replaces it in a follow-up. A null address (none set yet) means
 * no tax.
 */
interface TaxCalculator {
    suspend fun calculate(taxableAmount: Money, address: CartAddress?): Tax
}
