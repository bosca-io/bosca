package bosca.ecommerce.events

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.SubscriptionStatus
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * Round-trip serialization coverage for every concrete ecommerce [bosca.events.Event] data class.
 * These types serialize over PubSub (kotlinx.serialization), so the generated serializer branches —
 * including the "encode optional" and "skip optional" paths for nullable defaulted fields — must be
 * exercised. Uses explicit `.serializer()` (GraalVM native-safe) with the platform's contextual UUID
 * serializer, matching `core-ecommerce`'s `PrimitivesSerializationTest`.
 */
@OptIn(ExperimentalUuidApi::class)
class EcomEventsSerializationTest {

    private val json = Json {
        serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()) }
    }

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, value))

    // --- CartEvents ---

    @Test
    fun `CartCreated round-trips`() {
        val full = CartCreated(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
            accountId = UUID.random(),
            customerId = UUID.random(),
        )
        assertEquals(full, roundTrip(CartCreated.serializer(), full))
        // nullable accountId/customerId left null -> hits the "skip optional" branch
        val minimal = CartCreated(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
        )
        assertEquals(minimal, roundTrip(CartCreated.serializer(), minimal))
    }

    @Test
    fun `CartSubmitted round-trips`() {
        val event = CartSubmitted(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
        )
        assertEquals(event, roundTrip(CartSubmitted.serializer(), event))
    }

    @Test
    fun `CartPaid round-trips`() {
        val event = CartPaid(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
        )
        assertEquals(event, roundTrip(CartPaid.serializer(), event))
    }

    @Test
    fun `CartRefunded round-trips`() {
        val event = CartRefunded(cartId = UUID.random(), storeId = UUID.random())
        assertEquals(event, roundTrip(CartRefunded.serializer(), event))
    }

    @Test
    fun `CartVoided round-trips`() {
        val event = CartVoided(cartId = UUID.random(), storeId = UUID.random())
        assertEquals(event, roundTrip(CartVoided.serializer(), event))
    }

    @Test
    fun `CartExpired round-trips`() {
        val event = CartExpired(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
        )
        assertEquals(event, roundTrip(CartExpired.serializer(), event))
    }

    @Test
    fun `CartCompleted round-trips`() {
        val event = CartCompleted(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
        )
        assertEquals(event, roundTrip(CartCompleted.serializer(), event))
    }

    // --- InventoryEvents ---

    @Test
    fun `InventoryShipped round-trips`() {
        val event = InventoryShipped(
            inventoryId = UUID.random(),
            productId = UUID.random(),
            quantity = 7,
        )
        assertEquals(event, roundTrip(InventoryShipped.serializer(), event))
    }

    @Test
    fun `InventoryReservationReleased round-trips`() {
        val event = InventoryReservationReleased(
            inventoryId = UUID.random(),
            productId = UUID.random(),
            quantity = 3,
        )
        assertEquals(event, roundTrip(InventoryReservationReleased.serializer(), event))
    }

    // --- PaymentEvents ---

    @Test
    fun `PaymentConfirmed round-trips`() {
        val full = PaymentConfirmed(
            paymentId = UUID.random(),
            storeId = UUID.random(),
            amount = Money.of("19.99"),
            cartId = UUID.random(),
            subscriptionId = UUID.random(),
        )
        assertEquals(full, roundTrip(PaymentConfirmed.serializer(), full))
        // nullable cartId/subscriptionId left null -> "skip optional" branch
        val minimal = PaymentConfirmed(
            paymentId = UUID.random(),
            storeId = UUID.random(),
            amount = Money.of("1.00"),
        )
        assertEquals(minimal, roundTrip(PaymentConfirmed.serializer(), minimal))
    }

    @Test
    fun `PaymentVoided round-trips`() {
        val event = PaymentVoided(paymentId = UUID.random(), storeId = UUID.random())
        assertEquals(event, roundTrip(PaymentVoided.serializer(), event))
    }

    @Test
    fun `PaymentRefunded round-trips`() {
        val full = PaymentRefunded(
            paymentId = UUID.random(),
            storeId = UUID.random(),
            amount = Money.of("5.50"),
            parentId = UUID.random(),
            cartId = UUID.random(),
        )
        assertEquals(full, roundTrip(PaymentRefunded.serializer(), full))
        // nullable cartId left null -> "skip optional" branch
        val minimal = PaymentRefunded(
            paymentId = UUID.random(),
            storeId = UUID.random(),
            amount = Money.of("2.25"),
            parentId = UUID.random(),
        )
        assertEquals(minimal, roundTrip(PaymentRefunded.serializer(), minimal))
    }

    // --- ProductEvents ---

    @Test
    fun `ProductCreated round-trips`() {
        val event = ProductCreated(productId = UUID.random(), companyId = UUID.random())
        assertEquals(event, roundTrip(ProductCreated.serializer(), event))
    }

    @Test
    fun `ProductUpdated round-trips`() {
        val event = ProductUpdated(productId = UUID.random(), companyId = UUID.random())
        assertEquals(event, roundTrip(ProductUpdated.serializer(), event))
    }

    @Test
    fun `ProductDeleted round-trips`() {
        val event = ProductDeleted(productId = UUID.random(), companyId = UUID.random())
        assertEquals(event, roundTrip(ProductDeleted.serializer(), event))
    }

    // --- PromotionEvents ---

    @Test
    fun `PromotionRedeemed round-trips`() {
        val event = PromotionRedeemed(
            promotionId = UUID.random(),
            storeId = UUID.random(),
            accountId = UUID.random(),
            code = "SAVE10",
            cartId = UUID.random(),
        )
        assertEquals(event, roundTrip(PromotionRedeemed.serializer(), event))
    }

    // --- ShipmentEvents ---

    @Test
    fun `ShipmentCreated round-trips`() {
        val event = ShipmentCreated(
            shipmentId = UUID.random(),
            storeId = UUID.random(),
            cartId = UUID.random(),
            companyId = UUID.random(),
            fulfillmentCenterId = UUID.random(),
        )
        assertEquals(event, roundTrip(ShipmentCreated.serializer(), event))
    }

    @Test
    fun `ShipmentShipped round-trips`() {
        val event = ShipmentShipped(
            shipmentId = UUID.random(),
            storeId = UUID.random(),
            cartId = UUID.random(),
            companyId = UUID.random(),
            fulfillmentCenterId = UUID.random(),
        )
        assertEquals(event, roundTrip(ShipmentShipped.serializer(), event))
    }

    // --- SubscriptionEvents ---

    @Test
    fun `SubscriptionCreated round-trips`() {
        val full = SubscriptionCreated(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            storeId = UUID.random(),
            planId = UUID.random(),
            planGroupId = UUID.random(),
            cartId = UUID.random(),
        )
        assertEquals(full, roundTrip(SubscriptionCreated.serializer(), full))
        // nullable cartId left null -> "skip optional" branch
        val minimal = SubscriptionCreated(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            storeId = UUID.random(),
            planId = UUID.random(),
            planGroupId = UUID.random(),
        )
        assertEquals(minimal, roundTrip(SubscriptionCreated.serializer(), minimal))
    }

    @Test
    fun `SubscriptionStatusChanged round-trips`() {
        val event = SubscriptionStatusChanged(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            status = SubscriptionStatus.ACTIVE,
            previousStatus = SubscriptionStatus.PENDING,
        )
        assertEquals(event, roundTrip(SubscriptionStatusChanged.serializer(), event))
    }

    @Test
    fun `SubscriptionRenewed round-trips`() {
        val event = SubscriptionRenewed(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            paymentId = UUID.random(),
            renewals = 4,
        )
        assertEquals(event, roundTrip(SubscriptionRenewed.serializer(), event))
    }

    @Test
    fun `SubscriptionRenewalFailed round-trips`() {
        val event = SubscriptionRenewalFailed(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            paymentFailures = 2,
        )
        assertEquals(event, roundTrip(SubscriptionRenewalFailed.serializer(), event))
    }

    // --- Missing-required-field decode (the synthetic deserialization constructor's throw branch) ---
    // Every concrete event has at least one required (non-default) field, so decoding "{}" must throw
    // a MissingFieldException (a SerializationException subtype) from the generated deserializer.

    @Test
    fun `CartCreated rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartCreated.serializer(), "{}") }
    }

    @Test
    fun `CartSubmitted rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartSubmitted.serializer(), "{}") }
    }

    @Test
    fun `CartPaid rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartPaid.serializer(), "{}") }
    }

    @Test
    fun `CartRefunded rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartRefunded.serializer(), "{}") }
    }

    @Test
    fun `CartVoided rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartVoided.serializer(), "{}") }
    }

    @Test
    fun `CartExpired rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartExpired.serializer(), "{}") }
    }

    @Test
    fun `CartCompleted rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(CartCompleted.serializer(), "{}") }
    }

    @Test
    fun `InventoryShipped rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(InventoryShipped.serializer(), "{}") }
    }

    @Test
    fun `InventoryReservationReleased rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(InventoryReservationReleased.serializer(), "{}") }
    }

    @Test
    fun `PaymentConfirmed rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(PaymentConfirmed.serializer(), "{}") }
    }

    @Test
    fun `PaymentVoided rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(PaymentVoided.serializer(), "{}") }
    }

    @Test
    fun `PaymentRefunded rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(PaymentRefunded.serializer(), "{}") }
    }

    @Test
    fun `ProductCreated rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ProductCreated.serializer(), "{}") }
    }

    @Test
    fun `ProductUpdated rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ProductUpdated.serializer(), "{}") }
    }

    @Test
    fun `ProductDeleted rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ProductDeleted.serializer(), "{}") }
    }

    @Test
    fun `PromotionRedeemed rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(PromotionRedeemed.serializer(), "{}") }
    }

    @Test
    fun `ShipmentCreated rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ShipmentCreated.serializer(), "{}") }
    }

    @Test
    fun `ShipmentShipped rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(ShipmentShipped.serializer(), "{}") }
    }

    @Test
    fun `SubscriptionCreated rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(SubscriptionCreated.serializer(), "{}") }
    }

    @Test
    fun `SubscriptionStatusChanged rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(SubscriptionStatusChanged.serializer(), "{}") }
    }

    @Test
    fun `SubscriptionRenewed rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(SubscriptionRenewed.serializer(), "{}") }
    }

    @Test
    fun `SubscriptionRenewalFailed rejects missing required fields`() {
        assertFailsWith<SerializationException> { json.decodeFromString(SubscriptionRenewalFailed.serializer(), "{}") }
    }
}
