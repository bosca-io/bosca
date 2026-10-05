package bosca.ecommerce.service

import bosca.ecommerce.model.Return
import bosca.ecommerce.model.ReturnInput
import bosca.ecommerce.model.ReturnStatus
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Returns / RMAs against paid orders. Lifecycle: `request` (REQUESTED) → `approve` (APPROVED) →
 * `receive` (RECEIVED) → `refund` (REFUNDED); `reject` is the off-ramp from REQUESTED/APPROVED. The
 * `refund` step restocks the returned lines and issues the money back via the cart's `refundItems`
 * mechanic — returns add the workflow on top of the existing item-refund path.
 */
interface ReturnService : Service {

    suspend fun get(id: UUID): Return?

    /** Returns against an order (cart), newest first. */
    suspend fun getByCart(cartId: UUID): List<Return>

    /** A store's returns, newest first; [status] null = all states. */
    suspend fun getByStore(storeId: UUID, status: ReturnStatus?, offset: Int, limit: Int): List<Return>

    /** Open a return request for an order's lines (REQUESTED). Fails if the cart isn't a paid order. */
    suspend fun request(input: ReturnInput, principalId: UUID?): Return

    /** Approve a requested return (REQUESTED → APPROVED). */
    suspend fun approve(id: UUID, principalId: UUID?): Return

    /** Reject a return with a [reason] (REQUESTED/APPROVED → REJECTED). */
    suspend fun reject(id: UUID, reason: String?, principalId: UUID?): Return

    /** Mark the returned goods physically received/inspected (APPROVED → RECEIVED). */
    suspend fun receive(id: UUID, principalId: UUID?): Return

    /** Restock the lines and issue the refund (RECEIVED → REFUNDED) via the cart's item-refund path. */
    suspend fun refund(id: UUID, principalId: UUID?): Return
}
