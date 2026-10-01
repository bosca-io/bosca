package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.events.ReturnRefunded
import bosca.ecommerce.events.ReturnRequested
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.RefundItemInput
import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnInput
import bosca.ecommerce.model.ReturnLine
import bosca.ecommerce.model.ReturnStatus
import bosca.ecommerce.repository.ReturnRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Returns / RMAs. The workflow (request → approve → receive → refund, with reject) is new; the money +
 * inventory effects at the refund step reuse [CartService.refundItems] (reduce lines, restock the
 * reserved inventory, refund the delta to the chosen tender). Each transition takes a row lock and is
 * guarded so an out-of-order call fails loudly rather than corrupting the lifecycle.
 */
@ServiceImplementation
class ReturnServiceImpl(
    private val returnRepository: ReturnRepository,
    private val cartService: CartService,
    private val auditService: EcomAuditService,
) : ReturnService {

    override suspend fun get(id: UUID): Return? = returnRepository.get(id)

    override suspend fun getByCart(cartId: UUID): List<Return> = returnRepository.getByCart(cartId)

    override suspend fun getByStore(storeId: UUID, status: ReturnStatus?, offset: Int, limit: Int): List<Return> =
        if (status == null) returnRepository.getByStore(storeId, offset, limit)
        else returnRepository.getByStoreAndStatus(storeId, status, offset, limit)

    override suspend fun request(input: ReturnInput, principalId: UUID?): Return = transaction {
        val cart = cartService.get(input.cartId) ?: error("cart ${input.cartId} not found")
        check(cart.paid.isPositive) { "cart ${input.cartId} is not a paid order — nothing to return" }
        check(input.lines.isNotEmpty()) { "a return must include at least one line" }
        // Resolve each requested line against the order's items (validates the item belongs to the cart).
        val lines = input.lines.map { line ->
            val item = cart.items.firstOrNull { it.id == line.itemId } ?: error("item ${line.itemId} is not on cart ${input.cartId}")
            check(line.quantity in 1..item.quantity) { "return quantity ${line.quantity} exceeds ordered ${item.quantity} for item ${line.itemId}" }
            ReturnLine(itemId = item.id, catalogProductId = item.catalogProductId, quantity = line.quantity)
        }
        val created = returnRepository.add(
            Return(
                cartId = cart.id, storeId = cart.storeId, companyId = cart.companyId,
                status = ReturnStatus.REQUESTED, reason = input.reason, tender = input.tender,
                lines = lines, checkNumber = input.checkNumber,
            ),
        )
        audit(created, "return_requested", principalId)
        ReturnRequested(returnId = created.id, storeId = created.storeId, cartId = created.cartId, companyId = created.companyId).dispatch()
        created
    }

    override suspend fun approve(id: UUID, principalId: UUID?): Return =
        transition(id, from = ReturnStatus.REQUESTED, to = ReturnStatus.APPROVED, action = "return_approved", principalId = principalId)

    override suspend fun reject(id: UUID, reason: String?, principalId: UUID?): Return = transaction {
        val ret = lockOpen(id)
        check(ret.status == ReturnStatus.REQUESTED || ret.status == ReturnStatus.APPROVED) {
            "return $id cannot be rejected from ${ret.status}"
        }
        val rejected = returnRepository.update(ret.copy(status = ReturnStatus.REJECTED, reason = reason ?: ret.reason)) ?: ret
        audit(rejected, "return_rejected", principalId)
        rejected
    }

    override suspend fun receive(id: UUID, principalId: UUID?): Return =
        transition(id, from = ReturnStatus.APPROVED, to = ReturnStatus.RECEIVED, action = "return_received", principalId = principalId)

    override suspend fun refund(id: UUID, principalId: UUID?): Return = transaction {
        val ret = lockOpen(id)
        check(ret.status == ReturnStatus.RECEIVED) { "return $id cannot be refunded from ${ret.status} (must be RECEIVED)" }
        val beforeCart = cartService.get(ret.cartId)
        val before = beforeCart?.paid ?: Money.ZERO
        // Restock + refund the returned lines to the chosen tender; this is the existing item-refund path.
        val updated = cartService.refundItems(
            cartId = ret.cartId,
            items = ret.lines.map { RefundItemInput(itemId = it.itemId, quantity = it.quantity) },
            tender = ret.tender,
            checkNumber = ret.checkNumber,
            reason = ret.reason ?: "return ${ret.id}",
            principalId = principalId,
        )
        val refunded = (before - updated.paid).coerceAtLeast(Money.ZERO)
        val done = returnRepository.update(ret.copy(status = ReturnStatus.REFUNDED, refundedAmount = refunded)) ?: ret
        audit(done, "return_refunded", principalId)
        ReturnRefunded(returnId = done.id, storeId = done.storeId, cartId = done.cartId, companyId = done.companyId).dispatch()
        done
    }

    private suspend fun transition(id: UUID, from: ReturnStatus, to: ReturnStatus, action: String, principalId: UUID?): Return = transaction {
        val ret = lockOpen(id)
        check(ret.status == from) { "return $id cannot move to $to from ${ret.status} (must be $from)" }
        val updated = returnRepository.update(ret.copy(status = to)) ?: ret
        audit(updated, action, principalId)
        updated
    }

    private suspend fun lockOpen(id: UUID): Return = returnRepository.getForUpdate(id) ?: error("return $id not found")

    private suspend fun audit(returnEntity: Return, action: String, principalId: UUID?) {
        auditService.record(
            entityType = "return",
            entityId = returnEntity.id,
            action = action,
            serializer = Return.serializer(),
            after = returnEntity,
            principalId = principalId,
            storeId = returnEntity.storeId,
        )
    }
}
