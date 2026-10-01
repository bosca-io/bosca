package bosca.ecommerce.graphql

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartStatus
import bosca.ecommerce.model.CartStatusFlag
import bosca.ecommerce.model.CartSubmitResult
import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.TransactionType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** CartSubmitResult field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class CartSubmitResultControllerTest {

    private val controller = CartSubmitResultController()

    private val storeId = UUID.random()
    private val cart = Cart(
        id = UUID.random(), companyId = UUID.random(), storeId = storeId,
        status = CartStatus.of(CartStatusFlag.PAID), expires = OffsetDateTime.now().plusSeconds(3600),
    )
    private val payment = Payment(
        id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD,
        providerId = UUID.random(), storeId = storeId, amount = Money.of("10.00"),
    )
    private val subscription = Subscription(
        id = UUID.random(), storeId = storeId, accountId = UUID.random(), planId = UUID.random(),
        planGroupId = UUID.random(), price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
    )

    @Test
    fun `every field resolves from the source result`() {
        val source = CartSubmitResult(cart = cart, payments = listOf(payment), subscriptions = listOf(subscription))
        assertEquals(cart, controller.cart(source))
        assertEquals(listOf(payment), controller.payments(source))
        assertEquals(listOf(subscription), controller.subscriptions(source))
    }

    @Test
    fun `payments and subscriptions default to empty`() {
        val source = CartSubmitResult(cart = cart)
        assertEquals(emptyList(), controller.payments(source))
        assertEquals(emptyList(), controller.subscriptions(source))
    }
}
