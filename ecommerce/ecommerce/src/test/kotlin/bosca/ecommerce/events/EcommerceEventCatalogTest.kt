package bosca.ecommerce.events

import bosca.events.catalog.EcommerceEventCatalogRegistrarProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * acceptance: the ecommerce domain events are visible in the event catalog. KSP generates
 * [EcommerceEventCatalogRegistrarProvider] (a named `EventCatalogRegistrar`) from the `@JobEvent`
 * classes; `EventCatalogServiceImpl.list()` aggregates every registered registrar, so asserting this
 * one carries the full set is equivalent to asserting they appear under `events { catalog }`.
 */
class EcommerceEventCatalogTest {

    private val events = EcommerceEventCatalogRegistrarProvider().events

    @Test
    fun `every ecommerce domain event is registered in the catalog`() {
        val fqdns = events.map { it.fqdn }.toSet()
        val expected = setOf(
            "bosca.ecommerce.events.CartCreated",
            "bosca.ecommerce.events.CartSubmitted",
            "bosca.ecommerce.events.CartPaid",
            "bosca.ecommerce.events.CartRefunded",
            "bosca.ecommerce.events.CartVoided",
            "bosca.ecommerce.events.CartExpired",
            "bosca.ecommerce.events.CartCompleted",
            "bosca.ecommerce.events.PaymentConfirmed",
            "bosca.ecommerce.events.PaymentVoided",
            "bosca.ecommerce.events.PaymentRefunded",
            "bosca.ecommerce.events.SubscriptionCreated",
            "bosca.ecommerce.events.SubscriptionStatusChanged",
            "bosca.ecommerce.events.SubscriptionRenewed",
            "bosca.ecommerce.events.SubscriptionRenewalFailed",
            "bosca.ecommerce.events.InventoryShipped",
            "bosca.ecommerce.events.InventoryReservationReleased",
            "bosca.ecommerce.events.ShipmentCreated",
            "bosca.ecommerce.events.ShipmentShipped",
            "bosca.ecommerce.events.ReturnRequested",
            "bosca.ecommerce.events.ReturnRefunded",
            "bosca.ecommerce.events.ProductCreated",
            "bosca.ecommerce.events.ProductUpdated",
            "bosca.ecommerce.events.ProductDeleted",
            "bosca.ecommerce.events.PromotionRedeemed",
        )
        assertEquals(expected, fqdns)
    }

    @Test
    fun `the cart-paid descriptor carries its channel, display name, and filter fields`() {
        val paid = events.first { it.fqdn == "bosca.ecommerce.events.CartPaid" }
        assertEquals(CART_PAID_CHANNEL, paid.pubsubChannel)
        assertEquals("Cart Paid", paid.displayName)
        assertTrue(paid.jobNames.isEmpty())
        val fieldNames = paid.fields.map { it.name }.toSet()
        assertTrue(fieldNames.containsAll(setOf("cartId", "storeId", "companyId")))
    }

    @Test
    fun `every descriptor declares a pubsub channel under the ecommerce namespace`() {
        assertTrue(events.all { it.pubsubChannel?.startsWith("bosca.ecommerce.") == true })
    }
}
