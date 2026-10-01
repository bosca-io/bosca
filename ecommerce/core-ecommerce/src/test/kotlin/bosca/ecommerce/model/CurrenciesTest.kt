package bosca.ecommerce.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**settlement precision per currency, and Money quantization to it. */
class CurrenciesTest {

    @Test
    fun `settlementScale is 2 for ordinary currencies, 0 for zero-decimal, 3 for three-decimal`() {
        assertEquals(2, Currencies.settlementScale("USD"))
        assertEquals(2, Currencies.settlementScale("eur")) // case-insensitive
        assertEquals(0, Currencies.settlementScale("JPY"))
        assertEquals(0, Currencies.settlementScale("KRW"))
        assertEquals(3, Currencies.settlementScale("BHD"))
        assertEquals(3, Currencies.settlementScale("KWD"))
        assertEquals(2, Currencies.settlementScale("ZZZ")) // unknown -> default 2
    }

    @Test
    fun `quantize rounds to the settlement scale under HALF_EVEN`() {
        // 10.825 at scale 2 (HALF_EVEN, preceding digit 2 is even) rounds down to 10.82.
        assertEquals(Money.of("10.82"), Money.of("10.825").quantize(2))
        // 10.835 at scale 2 (preceding digit 3 is odd) rounds up to 10.84.
        assertEquals(Money.of("10.84"), Money.of("10.835").quantize(2))
        // Zero-decimal currency: quantize(0) drops to whole units (HALF_EVEN: ties round to even).
        assertEquals(Money.of("11"), Money.of("10.60").quantize(0))
        assertEquals(Money.of("10"), Money.of("10.40").quantize(0))
        assertEquals(Money.of("10"), Money.of("10.50").quantize(0)) // tie -> even (10)
        // Already-settleable amounts are unchanged.
        assertEquals(Money.of("20.00"), Money.of("20.00").quantize(2))
    }
}
