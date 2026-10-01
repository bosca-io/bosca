package bosca.ecommerce.events

import bosca.ecommerce.model.Money
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** Every domain event keys on its entity id (the dedupe/identity key for the trigger dispatcher). */
@OptIn(ExperimentalUuidApi::class)
class EcomEventsTest {

    @Test
    fun `event identity keys are the entity ids`() {
        val cart = UUID.random()
        assertEquals(cart, CartRefunded(cartId = cart, storeId = UUID.random()).identityKey())
        assertEquals(cart, CartVoided(cartId = cart, storeId = UUID.random()).identityKey())

        val inv = UUID.random()
        assertEquals(inv, InventoryShipped(inventoryId = inv, productId = UUID.random(), quantity = 1).identityKey())

        val pay = UUID.random()
        assertEquals(pay, PaymentConfirmed(paymentId = pay, storeId = UUID.random(), amount = Money.of("1.00")).identityKey())

        val prod = UUID.random()
        assertEquals(prod, ProductCreated(productId = prod, companyId = UUID.random()).identityKey())

        val ship = UUID.random()
        assertEquals(
            ship,
            ShipmentCreated(shipmentId = ship, storeId = UUID.random(), cartId = UUID.random(), companyId = UUID.random(), fulfillmentCenterId = UUID.random()).identityKey(),
        )

        val sub = UUID.random()
        assertEquals(
            sub,
            SubscriptionCreated(subscriptionId = sub, accountId = UUID.random(), storeId = UUID.random(), planId = UUID.random(), planGroupId = UUID.random()).identityKey(),
        )

        val promo = UUID.random()
        assertEquals(
            promo,
            PromotionRedeemed(promotionId = promo, storeId = UUID.random(), accountId = UUID.random(), code = "SAVE", cartId = UUID.random()).identityKey(),
        )
    }
}
