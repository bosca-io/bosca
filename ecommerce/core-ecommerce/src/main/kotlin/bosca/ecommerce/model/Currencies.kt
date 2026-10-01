package bosca.ecommerce.model

/**
 * Per-currency settlement precision. Most currencies settle to 2 decimal places, but zero-decimal
 * currencies (JPY, KRW, …) settle to whole units and three-decimal currencies (BHD, KWD, …) to mills.
 *
 * The money-of-record (a cart's sales total / due) is quantized to this scale before the charge-coverage
 * comparison so a recorded amount equals what a payment gateway can actually move — no uncollectable
 * sub-unit residual leaving a cart perpetually short of PAID. Mirrors the per-provider
 * minor-unit tables (e.g. Stripe's `minorUnits`) but lives in the domain so the cart can use it directly.
 */
object Currencies {
    private val ZERO_DECIMAL = setOf(
        "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF",
    )
    private val THREE_DECIMAL = setOf("BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND")

    /** Settlement (minor-unit) decimal places for [currency]: 0 for zero-decimal, 3 for three-decimal, else 2. */
    fun settlementScale(currency: String): Int = when (currency.uppercase()) {
        in ZERO_DECIMAL -> 0
        in THREE_DECIMAL -> 3
        else -> 2
    }
}
