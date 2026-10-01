package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Tax
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tax field wiring: every resolver returns the matching source jurisdiction component. */
class TaxControllerTest {

    private val controller = TaxController()
    private val tax = Tax.of(
        country = Money.of("1.00"),
        state = Money.of("2.00"),
        city = Money.of("0.50"),
        district = Money.of("0.25"),
    )

    @Test
    fun `every field resolves from the source tax`() {
        assertEquals(Money.of("1.00"), controller.country(tax))
        assertEquals(Money.of("2.00"), controller.state(tax))
        assertEquals(Money.of("0.50"), controller.city(tax))
        assertEquals(Money.of("0.25"), controller.district(tax))
        assertEquals(tax.taxes, controller.taxes(tax)) // derived line total = 3.75
    }
}
