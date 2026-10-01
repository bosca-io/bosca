@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.Cart
import bosca.ecommerce.model.CartItem
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.ProductType
import bosca.ecommerce.model.RefundTender
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnInput
import bosca.ecommerce.model.ReturnLine
import bosca.ecommerce.model.ReturnLineInput
import bosca.ecommerce.model.ReturnStatus
import bosca.ecommerce.repository.ReturnRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Returns/RMA lifecycle: request → approve → receive → refund (drives refundItems), reject off-ramp, and transition guards. */
@OptIn(ExperimentalUuidApi::class)
class ReturnServiceImplTest {

    private val returnRepository = mockk<ReturnRepository>()
    private val cartService = mockk<CartService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: ReturnServiceImpl

    private val returnId = UUID.random()
    private val cartId = UUID.random()
    private val storeId = UUID.random()
    private val companyId = UUID.random()
    private val itemId = UUID.random()
    private val cpId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        service = ReturnServiceImpl(returnRepository, cartService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        bosca.di.ProviderRegistry.clear()
    }

    private fun paidCart(paid: String = "20.00") = Cart(
        id = cartId, companyId = companyId, storeId = storeId, expires = OffsetDateTime.now().plusSeconds(3600),
        items = listOf(CartItem(id = itemId, catalogProductId = cpId, type = ProductType.PHYSICAL, quantity = 2)),
        paid = Money.of(paid),
    )

    private fun ret(status: ReturnStatus) = Return(
        id = returnId, cartId = cartId, storeId = storeId, companyId = companyId, status = status,
        tender = RefundTender.ORIGINAL, lines = listOf(ReturnLine(itemId = itemId, catalogProductId = cpId, quantity = 1)),
    )

    private fun captureUpdate(): CapturingSlot<Return> {
        val slot = slot<Return>()
        coEvery { returnRepository.update(capture(slot)) } answers { slot.captured }
        return slot
    }

    private fun input(quantity: Int = 1, lines: List<ReturnLineInput>? = null) =
        ReturnInput(cartId = cartId, reason = "changed mind", tender = RefundTender.ORIGINAL, lines = lines ?: listOf(ReturnLineInput(itemId, quantity)))

    // ---- request ----

    @Test
    fun `request resolves lines against the order and creates a REQUESTED return`() = runTest {
        coEvery { cartService.get(cartId) } returns paidCart()
        val added = slot<Return>()
        coEvery { returnRepository.add(capture(added)) } answers { added.captured.copy(id = returnId) }

        val result = service.request(input(quantity = 2), principalId = null)

        assertEquals(ReturnStatus.REQUESTED, added.captured.status)
        assertEquals(1, added.captured.lines.size)
        assertEquals(cpId, added.captured.lines.first().catalogProductId)
        assertEquals(2, added.captured.lines.first().quantity)
        assertEquals(returnId, result.id)
        coVerify { auditService.record<Return>(eq("return"), eq(returnId), eq("return_requested"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `request rejects an unknown cart, an unpaid order, empty lines, an unknown item, and a bad quantity`() = runTest {
        coEvery { cartService.get(cartId) } returns null
        assertFailsWith<IllegalStateException> { service.request(input(), null) } // cart not found

        coEvery { cartService.get(cartId) } returns paidCart(paid = "0.00")
        assertFailsWith<IllegalStateException> { service.request(input(), null) } // not a paid order

        coEvery { cartService.get(cartId) } returns paidCart()
        assertFailsWith<IllegalStateException> { service.request(input(lines = emptyList()), null) } // no lines
        assertFailsWith<IllegalStateException> { service.request(input(lines = listOf(ReturnLineInput(UUID.random(), 1))), null) } // item not on cart
        assertFailsWith<IllegalStateException> { service.request(input(quantity = 3), null) } // exceeds ordered (2)
        assertFailsWith<IllegalStateException> { service.request(input(quantity = 0), null) } // below 1
    }

    // ---- approve / receive / transition guards ----

    @Test
    fun `approve moves REQUESTED to APPROVED`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.REQUESTED)
        val updated = captureUpdate()
        service.approve(returnId, null)
        assertEquals(ReturnStatus.APPROVED, updated.captured.status)
    }

    @Test
    fun `receive moves APPROVED to RECEIVED`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.APPROVED)
        val updated = captureUpdate()
        service.receive(returnId, null)
        assertEquals(ReturnStatus.RECEIVED, updated.captured.status)
    }

    @Test
    fun `a transition from the wrong state, or on a missing return, fails`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.RECEIVED)
        assertFailsWith<IllegalStateException> { service.approve(returnId, null) } // not REQUESTED
        coEvery { returnRepository.getForUpdate(returnId) } returns null
        assertFailsWith<IllegalStateException> { service.approve(returnId, null) } // not found
    }

    // ---- reject ----

    @Test
    fun `reject is allowed from REQUESTED and APPROVED, with the reason recorded`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.REQUESTED)
        val updated = captureUpdate()
        service.reject(returnId, "out of policy", null)
        assertEquals(ReturnStatus.REJECTED, updated.captured.status)
        assertEquals("out of policy", updated.captured.reason)

        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.APPROVED)
        service.reject(returnId, null, null) // null reason keeps the existing one
        assertEquals(ReturnStatus.REJECTED, updated.captured.status)
    }

    @Test
    fun `reject fails from a terminal state`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.REFUNDED)
        assertFailsWith<IllegalStateException> { service.reject(returnId, "x", null) }
    }

    // ---- refund ----

    @Test
    fun `refund restocks and refunds via refundItems and records the amount`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.RECEIVED).copy(reason = "defective")
        coEvery { cartService.get(cartId) } returns paidCart(paid = "20.00")
        coEvery { cartService.refundItems(eq(cartId), any(), eq(RefundTender.ORIGINAL), any(), any(), any()) } returns paidCart(paid = "12.00")
        val updated = captureUpdate()

        val result = service.refund(returnId, null)

        assertEquals(ReturnStatus.REFUNDED, updated.captured.status)
        assertEquals(Money.of("8.00"), updated.captured.refundedAmount) // 20 paid - 12 after
        assertEquals(ReturnStatus.REFUNDED, result.status)
        coVerify { cartService.refundItems(eq(cartId), match { it.size == 1 && it.first().itemId == itemId }, any(), any(), any(), any()) }
    }

    @Test
    fun `refund records zero when the order is gone or the refund would be negative`() = runTest {
        // cartService.get returns null (before = ZERO) and refundItems reports a higher paid -> refunded coerces to ZERO.
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.RECEIVED)
        coEvery { cartService.get(cartId) } returns null
        coEvery { cartService.refundItems(any(), any(), any(), any(), any(), any()) } returns paidCart(paid = "5.00")
        val updated = captureUpdate()
        service.refund(returnId, null)
        assertEquals(Money.ZERO, updated.captured.refundedAmount)
    }

    @Test
    fun `refund fails when the return is not RECEIVED`() = runTest {
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.APPROVED)
        assertFailsWith<IllegalStateException> { service.refund(returnId, null) }
    }

    @Test
    fun `transitions fall back to the locked return when the update row vanishes`() = runTest {
        // update returns null (row gone mid-transaction) -> each path returns the locked return unchanged.
        coEvery { returnRepository.update(any()) } returns null
        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.REQUESTED)
        assertEquals(ReturnStatus.REQUESTED, service.approve(returnId, null).status)   // transition ?: ret
        assertEquals(ReturnStatus.REQUESTED, service.reject(returnId, "x", null).status) // reject ?: ret

        coEvery { returnRepository.getForUpdate(returnId) } returns ret(ReturnStatus.RECEIVED)
        coEvery { cartService.get(cartId) } returns paidCart()
        coEvery { cartService.refundItems(any(), any(), any(), any(), any(), any()) } returns paidCart(paid = "12.00")
        assertEquals(ReturnStatus.RECEIVED, service.refund(returnId, null).status)      // refund ?: ret
    }

    // ---- reads ----

    @Test
    fun `reads delegate to the repository, by store with and without a status filter`() = runTest {
        coEvery { returnRepository.get(returnId) } returns ret(ReturnStatus.REQUESTED)
        coEvery { returnRepository.getByCart(cartId) } returns listOf(ret(ReturnStatus.REQUESTED))
        coEvery { returnRepository.getByStore(storeId, 0, 50) } returns listOf(ret(ReturnStatus.REQUESTED))
        coEvery { returnRepository.getByStoreAndStatus(storeId, ReturnStatus.REFUNDED, 0, 50) } returns emptyList()

        assertEquals(returnId, service.get(returnId)?.id)
        assertEquals(1, service.getByCart(cartId).size)
        assertEquals(1, service.getByStore(storeId, null, 0, 50).size)
        assertEquals(0, service.getByStore(storeId, ReturnStatus.REFUNDED, 0, 50).size)
    }
}
