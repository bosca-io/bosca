package bosca.ecommerce.service

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.EmptyProviderConfiguration
import bosca.ecommerce.model.KeyValueProviderConfiguration
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Shipment
import bosca.ecommerce.model.ShipmentStatus
import bosca.ecommerce.model.ShippingProvider
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**the fixed-rate test shipping provider. */
@OptIn(ExperimentalUuidApi::class)
class FixedRateShippingRateProviderTest {

    private val provider = FixedRateShippingRateProvider()

    private fun config(amount: String?) = ShippingProvider(
        id = UUID.random(), companyId = UUID.random(), name = "Flat", key = "flat-1", providerKey = "fixed-rate",
        configuration = amount?.let { KeyValueProviderConfiguration(mapOf("amount" to it)) } ?: EmptyProviderConfiguration,
    )

    private fun cart() = Cart(companyId = UUID.random(), storeId = UUID.random(), expires = OffsetDateTime.now().plusSeconds(3600))

    private fun shipment(status: ShipmentStatus) = Shipment(
        cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random(),
        fulfillmentCenterId = UUID.random(), status = status,
    )

    @Test
    fun `key is fixed-rate`() {
        assertEquals("fixed-rate", provider.key)
    }

    @Test
    fun `quotes the default rate when unconfigured`() = runTest {
        val rates = provider.rates(config(null), cart(), null, null, emptyList())
        assertEquals(1, rates.size)
        assertEquals(Money.of("5.00"), rates.first().amount)
    }

    @Test
    fun `quotes the configured amount`() = runTest {
        val rates = provider.rates(config("12.50"), cart(), null, null, emptyList())
        assertEquals(Money.of("12.50"), rates.first().amount)
    }

    @Test
    fun `quotes the default rate when the configuration is not a key-value config`() = runTest {
        // EmptyProviderConfiguration is not a KeyValueProviderConfiguration -> the `as?` is null.
        val rates = provider.rates(config(null), cart(), null, null, emptyList())
        assertEquals(Money.of("5.00"), rates.first().amount)
    }

    @Test
    fun `quotes the default rate when the key-value config has no amount key`() = runTest {
        // A KeyValueProviderConfiguration that simply omits "amount" -> values[amount] is null -> default.
        val cfg = ShippingProvider(
            id = UUID.random(), companyId = UUID.random(), name = "Flat", key = "flat-1", providerKey = "fixed-rate",
            configuration = KeyValueProviderConfiguration(mapOf("other" to "9.99")),
        )
        val rates = provider.rates(cfg, cart(), null, null, emptyList())
        assertEquals(Money.of("5.00"), rates.first().amount)
    }

    @Test
    fun `the quoted rate carries the carrier name and a per-provider token`() = runTest {
        val cfg = config("7.00")
        val rate = provider.rates(cfg, cart(), null, null, emptyList()).first()
        assertEquals(cfg.name, rate.carrier)
        assertEquals("Standard", rate.serviceLevel)
        assertNull(rate.durationTerms)
        assertEquals("fixed:${cfg.id}", rate.token)
    }

    @Test
    fun `purchaseLabel assigns the carrier name and no real tracking or label`() = runTest {
        val cfg = config(null)
        val label = provider.purchaseLabel(cfg, shipment(ShipmentStatus.AWAITING), null, null, emptyList())
        assertEquals(cfg.name, label.carrier)
        assertNull(label.tracking)
        assertNull(label.labelUrl)
    }

    @Test
    fun `track advances SHIPPED to IN_TRANSIT`() = runTest {
        val update = provider.track(config(null), shipment(ShipmentStatus.SHIPPED))
        assertNotNull(update)
        assertEquals(ShipmentStatus.IN_TRANSIT, update.status)
        assertEquals("Flat: in transit", update.carrierStatus)
        assertNull(update.delivered)
    }

    @Test
    fun `track advances IN_TRANSIT to OUT_FOR_DELIVERY`() = runTest {
        val update = provider.track(config(null), shipment(ShipmentStatus.IN_TRANSIT))
        assertNotNull(update)
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, update.status)
        assertEquals("Flat: out for delivery", update.carrierStatus)
        assertNull(update.delivered)
    }

    @Test
    fun `track advances OUT_FOR_DELIVERY to DELIVERED and stamps a delivery time`() = runTest {
        val update = provider.track(config(null), shipment(ShipmentStatus.OUT_FOR_DELIVERY))
        assertNotNull(update)
        assertEquals(ShipmentStatus.DELIVERED, update.status)
        assertEquals("Flat: delivered", update.carrierStatus)
        // DELIVERED is the only branch that stamps a delivery time.
        assertNotNull(update.delivered)
    }

    @Test
    fun `track returns null for a shipment not in flight`() = runTest {
        // AWAITING (and any already-terminal state) maps to the `else` branch -> no update.
        assertNull(provider.track(config(null), shipment(ShipmentStatus.AWAITING)))
        assertNull(provider.track(config(null), shipment(ShipmentStatus.DELIVERED)))
        assertNull(provider.track(config(null), shipment(ShipmentStatus.CANCELLED)))
    }
}
