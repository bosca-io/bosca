package bosca.ecommerce.graphql

import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnLine
import bosca.ecommerce.model.ReturnStatus
import bosca.serialization.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

/** EcomReturn + EcomReturnLine field wiring: every resolver returns the matching source field. */
@OptIn(ExperimentalUuidApi::class)
class ReturnControllerTest {

    private val controller = ReturnController()
    private val line = ReturnLine(itemId = UUID.random(), catalogProductId = UUID.random(), quantity = 2)
    private val ret = Return(
        id = UUID.random(), cartId = UUID.random(), storeId = UUID.random(), companyId = UUID.random(),
        status = ReturnStatus.REFUNDED, reason = "changed mind", tender = RefundTender.CHECK,
        lines = listOf(line), refundedAmount = Money.of("8.00"), checkNumber = "CHK-1",
    )

    @Test
    fun `every field resolves from the source return`() {
        assertEquals(ret.id, controller.id(ret))
        assertEquals(ret.cartId, controller.cartId(ret))
        assertEquals(ret.storeId, controller.storeId(ret))
        assertEquals(ret.companyId, controller.companyId(ret))
        assertEquals(ReturnStatus.REFUNDED, controller.status(ret))
        assertEquals("changed mind", controller.reason(ret))
        assertEquals(RefundTender.CHECK, controller.tender(ret))
        assertEquals(listOf(line), controller.lines(ret))
        assertEquals(Money.of("8.00"), controller.refundedAmount(ret))
        assertEquals("CHK-1", controller.checkNumber(ret))
        assertEquals(ret.created, controller.created(ret))
        assertEquals(ret.modified, controller.modified(ret))
    }

    @Test
    fun `line field controller resolves itemId, catalogProductId and quantity`() {
        val lineController = ReturnLineController()
        assertEquals(line.itemId, lineController.itemId(line))
        assertEquals(line.catalogProductId, lineController.catalogProductId(line))
        assertEquals(2, lineController.quantity(line))
    }
}
