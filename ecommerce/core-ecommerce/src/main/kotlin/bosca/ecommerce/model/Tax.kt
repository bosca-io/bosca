package bosca.ecommerce.model

import kotlinx.serialization.Serializable

/**
 * Per-jurisdiction tax breakdown for one cart line. [taxes] is the line total (the sum of the
 * jurisdiction components). Embedded in cart items (jsonb), computed by the [bosca.ecommerce] tax
 * calculator during totals recomputation.
 */
@Serializable
data class Tax(
    val country: Money = Money.ZERO,
    val state: Money = Money.ZERO,
    val city: Money = Money.ZERO,
    val district: Money = Money.ZERO,
    val taxes: Money = Money.ZERO,
) {
    init {
        // [taxes] is the line total — it MUST equal the sum of its jurisdiction components. Enforced on every
        // construction, including deserialization from jsonb: all writers go through [of] (and the tax
        // calculators), so persisted values are already consistent and reads stay safe.
        require(taxes == country + state + city + district) {
            "Tax.taxes ($taxes) must equal the sum of its components (country+state+city+district = ${country + state + city + district})"
        }
    }

    companion object {
        val ZERO = Tax()

        /** Build a [Tax] from its components, deriving [taxes] as their sum. */
        fun of(
            country: Money = Money.ZERO,
            state: Money = Money.ZERO,
            city: Money = Money.ZERO,
            district: Money = Money.ZERO,
        ): Tax = Tax(country, state, city, district, country + state + city + district)
    }
}
