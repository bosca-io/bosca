package bosca.ecommerce.service

import bosca.ecommerce.model.AddressType
import bosca.ecommerce.model.CartAddress
import bosca.ecommerce.model.Money
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**default state-rate tax calculation. */
@OptIn(ExperimentalUuidApi::class)
class DefaultTaxCalculatorTest {

    private fun address(state: String) = CartAddress(
        cartId = UUID.random(), type = AddressType.SHIPPING, firstName = "A", lastName = "B",
        address1 = "1 St", city = "City", state = state, country = "US", zip = "00000", phone = "555",
    )

    @Test
    fun `applies the configured state rate`() = runTest {
        val calc = DefaultTaxCalculator(mapOf("CA" to 8.0))
        val tax = calc.calculate(Money.of("100.00"), address("CA"))
        assertEquals(Money.of("8.00"), tax.state)
        assertEquals(Money.of("8.00"), tax.taxes)
    }

    @Test
    fun `no tax for an unconfigured state`() = runTest {
        val calc = DefaultTaxCalculator(mapOf("CA" to 8.0))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, calc.calculate(Money.of("100.00"), address("NY")))
    }

    @Test
    fun `no tax without an address`() = runTest {
        val calc = DefaultTaxCalculator(mapOf("CA" to 8.0))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, calc.calculate(Money.of("100.00"), null))
    }

    @Test
    fun `no tax for a state configured at a zero rate`() = runTest {
        // A configured-but-zero rate trips the `if (rate <= 0.0)` guard rather than computing a 0% tax.
        val calc = DefaultTaxCalculator(mapOf("CA" to 0.0))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, calc.calculate(Money.of("100.00"), address("CA")))
    }

    @Test
    fun `no tax for a state configured at a negative rate`() = runTest {
        val calc = DefaultTaxCalculator(mapOf("CA" to -5.0))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, calc.calculate(Money.of("100.00"), address("CA")))
    }

    @Test
    fun `no tax when the address has no state`() = runTest {
        // address.state is the empty string -> not in the rate map -> the `?:` falls through to ZERO.
        val calc = DefaultTaxCalculator(mapOf("CA" to 8.0))
        assertEquals(bosca.ecommerce.model.Tax.ZERO, calc.calculate(Money.of("100.00"), address("")))
    }
}
