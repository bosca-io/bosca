package bosca.ecommerce.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**`Tax.taxes` must always equal the sum of its jurisdiction components. */
class TaxTest {

    @Test
    fun `of derives taxes as the sum of the components`() {
        val tax = Tax.of(
            country = Money.of("0.05"), state = Money.of("1.00"), city = Money.of("0.15"), district = Money.of("0.30"),
        )
        assertEquals(Money.of("1.50"), tax.taxes)
    }

    @Test
    fun `ZERO is consistent`() {
        assertEquals(Money.ZERO, Tax.ZERO.taxes)
    }

    @Test
    fun `a consistent explicit total is accepted`() {
        val tax = Tax(state = Money.of("1.00"), city = Money.of("0.50"), taxes = Money.of("1.50"))
        assertEquals(Money.of("1.50"), tax.taxes)
    }

    @Test
    fun `an inconsistent total is rejected on construction`() {
        assertFailsWith<IllegalArgumentException> {
            Tax(country = Money.of("1.00"), taxes = Money.of("2.00")) // 2.00 != 1.00
        }
    }
}
