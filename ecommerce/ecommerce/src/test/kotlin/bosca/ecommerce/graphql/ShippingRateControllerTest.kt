package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ShippingRate
import kotlin.test.Test
import kotlin.test.assertEquals

/** ShippingRate field wiring: every resolver returns the matching source field. */
class ShippingRateControllerTest {

    private val controller = ShippingRateController()
    private val rate = ShippingRate(
        carrier = "USPS",
        serviceLevel = "Priority",
        durationTerms = "2-3 days",
        token = "rate-token-123",
        amount = Money.of("9.99"),
    )

    @Test
    fun `every field resolves from the source rate`() {
        assertEquals("USPS", controller.carrier(rate))
        assertEquals("Priority", controller.serviceLevel(rate))
        assertEquals("2-3 days", controller.durationTerms(rate))
        assertEquals("rate-token-123", controller.token(rate))
        assertEquals(Money.of("9.99"), controller.amount(rate))
    }
}
