package bosca.ecommerce.events

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.SubscriptionStatus
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi

/**
 * Branch coverage for the compiler-generated `equals()`/`hashCode()` of every ecom event `data class`.
 * Each test hits the reflexive (`===`), all-fields-equal, `!is` (wrong type), and per-field `!=`
 * branches. Nullable fields additionally vary null <-> non-null.
 */
@OptIn(ExperimentalUuidApi::class)
class EcomEventsEqualityTest {

    // --- CartEvents.kt ---

    @Test
    fun `CartCreated equality`() {
        val a = CartCreated(
            cartId = UUID.random(),
            storeId = UUID.random(),
            companyId = UUID.random(),
            accountId = UUID.random(),
            customerId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = null))
        assertNotEquals(a, a.copy(customerId = UUID.random()))
        assertNotEquals(a, a.copy(customerId = null))
    }

    @Test
    fun `CartSubmitted equality`() {
        val a = CartSubmitted(cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    @Test
    fun `CartPaid equality`() {
        val a = CartPaid(cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    @Test
    fun `CartRefunded equality`() {
        val a = CartRefunded(cartId = UUID.random(), storeId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
    }

    @Test
    fun `CartVoided equality`() {
        val a = CartVoided(cartId = UUID.random(), storeId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
    }

    @Test
    fun `CartExpired equality`() {
        val a = CartExpired(cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    @Test
    fun `CartCompleted equality`() {
        val a = CartCompleted(cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    // --- InventoryEvents.kt ---

    @Test
    fun `InventoryShipped equality`() {
        val a = InventoryShipped(inventoryId = UUID.random(), productId = UUID.random(), quantity = 1)
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(inventoryId = UUID.random()))
        assertNotEquals(a, a.copy(productId = UUID.random()))
        assertNotEquals(a, a.copy(quantity = a.quantity + 1))
    }

    @Test
    fun `InventoryReservationReleased equality`() {
        val a = InventoryReservationReleased(inventoryId = UUID.random(), productId = UUID.random(), quantity = 1)
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(inventoryId = UUID.random()))
        assertNotEquals(a, a.copy(productId = UUID.random()))
        assertNotEquals(a, a.copy(quantity = a.quantity + 1))
    }

    // --- PaymentEvents.kt ---

    @Test
    fun `PaymentConfirmed equality`() {
        val a = PaymentConfirmed(
            paymentId = UUID.random(),
            storeId = UUID.random(),
            amount = Money.of("1.00"),
            cartId = UUID.random(),
            subscriptionId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(paymentId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(amount = Money.of("2.00")))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = null))
        assertNotEquals(a, a.copy(subscriptionId = UUID.random()))
        assertNotEquals(a, a.copy(subscriptionId = null))
    }

    @Test
    fun `PaymentVoided equality`() {
        val a = PaymentVoided(paymentId = UUID.random(), storeId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(paymentId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
    }

    @Test
    fun `PaymentRefunded equality`() {
        val a = PaymentRefunded(
            paymentId = UUID.random(),
            storeId = UUID.random(),
            amount = Money.of("1.00"),
            parentId = UUID.random(),
            cartId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(paymentId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(amount = Money.of("2.00")))
        assertNotEquals(a, a.copy(parentId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = null))
    }

    // --- ProductEvents.kt ---

    @Test
    fun `ProductCreated equality`() {
        val a = ProductCreated(productId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(productId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    @Test
    fun `ProductUpdated equality`() {
        val a = ProductUpdated(productId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(productId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    @Test
    fun `ProductDeleted equality`() {
        val a = ProductDeleted(productId = UUID.random(), companyId = UUID.random())
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(productId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
    }

    // --- PromotionEvents.kt ---

    @Test
    fun `PromotionRedeemed equality`() {
        val a = PromotionRedeemed(
            promotionId = UUID.random(),
            storeId = UUID.random(),
            accountId = UUID.random(),
            code = "SAVE",
            cartId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(promotionId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = UUID.random()))
        assertNotEquals(a, a.copy(code = "OTHER"))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
    }

    // --- ShipmentEvents.kt ---

    @Test
    fun `ShipmentCreated equality`() {
        val a = ShipmentCreated(
            shipmentId = UUID.random(),
            storeId = UUID.random(),
            cartId = UUID.random(),
            companyId = UUID.random(),
            fulfillmentCenterId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(shipmentId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
        assertNotEquals(a, a.copy(fulfillmentCenterId = UUID.random()))
    }

    @Test
    fun `ShipmentShipped equality`() {
        val a = ShipmentShipped(
            shipmentId = UUID.random(),
            storeId = UUID.random(),
            cartId = UUID.random(),
            companyId = UUID.random(),
            fulfillmentCenterId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(shipmentId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(companyId = UUID.random()))
        assertNotEquals(a, a.copy(fulfillmentCenterId = UUID.random()))
    }

    // --- SubscriptionEvents.kt ---

    @Test
    fun `SubscriptionCreated equality`() {
        val a = SubscriptionCreated(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            storeId = UUID.random(),
            planId = UUID.random(),
            planGroupId = UUID.random(),
            cartId = UUID.random(),
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(subscriptionId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = UUID.random()))
        assertNotEquals(a, a.copy(storeId = UUID.random()))
        assertNotEquals(a, a.copy(planId = UUID.random()))
        assertNotEquals(a, a.copy(planGroupId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = UUID.random()))
        assertNotEquals(a, a.copy(cartId = null))
    }

    @Test
    fun `SubscriptionStatusChanged equality`() {
        val a = SubscriptionStatusChanged(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            status = SubscriptionStatus.ACTIVE,
            previousStatus = SubscriptionStatus.PENDING,
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(subscriptionId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = UUID.random()))
        assertNotEquals(a, a.copy(status = SubscriptionStatus.CANCELLED))
        assertNotEquals(a, a.copy(previousStatus = SubscriptionStatus.TRIALING))
    }

    @Test
    fun `SubscriptionRenewed equality`() {
        val a = SubscriptionRenewed(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            paymentId = UUID.random(),
            renewals = 1,
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(subscriptionId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = UUID.random()))
        assertNotEquals(a, a.copy(paymentId = UUID.random()))
        assertNotEquals(a, a.copy(renewals = a.renewals + 1))
    }

    @Test
    fun `SubscriptionRenewalFailed equality`() {
        val a = SubscriptionRenewalFailed(
            subscriptionId = UUID.random(),
            accountId = UUID.random(),
            paymentFailures = 1,
        )
        assertEquals(a, a)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
        assertFalse(a.equals("not an event"))
        assertNotEquals(a, a.copy(subscriptionId = UUID.random()))
        assertNotEquals(a, a.copy(accountId = UUID.random()))
        assertNotEquals(a, a.copy(paymentFailures = a.paymentFailures + 1))
    }
}
