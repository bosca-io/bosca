package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.TransactionType
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** Payment field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class PaymentControllerTest {

    private val controller = PaymentController()
    private val parentId = UUID.random()
    private val payment = Payment(
        id = UUID.random(),
        transactionType = TransactionType.REFUND,
        type = PaymentType.CREDIT_CARD,
        providerId = UUID.random(),
        storeId = UUID.random(),
        parentId = parentId,
        amount = Money.of("20.00"),
        nonRefundableAmount = Money.of("1.00"),
        refundedAmount = Money.of("5.00"),
        complete = true,
        confirmed = true,
        voided = bosca.serialization.OffsetDateTime.now(),
        voidedReason = "dup",
        refunded = bosca.serialization.OffsetDateTime.now(),
        refundReason = "partial",
        note = "a note",
        providerTransactionId = "tx-1",
        providerStatus = "settled",
    )

    @Test
    fun `every field resolves from the source payment`() {
        assertEquals(payment.id, controller.id(payment))
        assertEquals(TransactionType.REFUND, controller.transactionType(payment))
        assertEquals(PaymentType.CREDIT_CARD, controller.type(payment))
        assertEquals(parentId, controller.parentId(payment))
        assertEquals(Money.of("20.00"), controller.amount(payment))
        assertEquals(Money.of("1.00"), controller.nonRefundableAmount(payment))
        assertEquals(Money.of("5.00"), controller.refundedAmount(payment))
        assertEquals(true, controller.complete(payment))
        assertEquals(true, controller.confirmed(payment))
        assertEquals(payment.voided, controller.voided(payment))
        assertEquals("dup", controller.voidedReason(payment))
        assertEquals(payment.refunded, controller.refunded(payment))
        assertEquals("partial", controller.refundReason(payment))
        assertEquals("a note", controller.note(payment))
        assertEquals("tx-1", controller.providerTransactionId(payment))
        assertEquals("settled", controller.providerStatus(payment))
        assertEquals(payment.created, controller.created(payment))
        assertEquals(payment.modified, controller.modified(payment))
    }
}
