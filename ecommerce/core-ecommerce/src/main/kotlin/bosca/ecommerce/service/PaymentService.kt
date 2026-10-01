package bosca.ecommerce.service

import bosca.ecommerce.model.ChargePaymentInput
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentSubmitResult
import bosca.ecommerce.model.SavedChargeInput
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Payments. Resolves the store's payment provider (config row) to a DI-registered [PaymentProcessor]
 * by `providerKey`, records each money movement (charge/refund/void/refund-to-credit) with the
 * gateway's evidence, and links refunds to their original via the parent chain.
 */
interface PaymentService : Service {

    /** A payment by id. */
    suspend fun get(id: UUID): Payment?

    /** Payments for an account, newest first (admin). */
    suspend fun getByAccount(accountId: UUID, offset: Int, limit: Int): List<Payment>

    /** Payments recorded in the `[start, end)` window, newest first (legacy date-range reporting). */
    suspend fun getByDateRange(start: OffsetDateTime, end: OffsetDateTime, offset: Int, limit: Int): List<Payment>

    /** A page of a cart's payments (charges/refunds/voids), oldest first — the bounded API path. */
    suspend fun getByCart(cartId: UUID, offset: Int, limit: Int): List<Payment>

    /** ALL of a cart's payments, oldest first — for settlement/refund math that must see every tender. */
    suspend fun getAllByCart(cartId: UUID): List<Payment>

    /** Charge a payment (optionally saving the method); records the payment + returns any save. */
    suspend fun charge(input: ChargePaymentInput, principalId: UUID?): PaymentSubmitResult

    /**
     * Re-bill a saved method (renewals); records the payment. The result carries
     * [PaymentSubmitResult.transportFailure] so the renewal engine can tell a gateway outage from a
     * card decline and avoid dunning on the former.
     */
    suspend fun chargeSaved(input: SavedChargeInput, principalId: UUID?): PaymentSubmitResult

    /**
     * Confirm a recorded CHECK payment has cleared (legacy `confirmCheck`): stamps `checkConfirmed`,
     * sets `confirmed = true` (optionally updating the check number), and emits the confirmed event.
     * Until confirmed a check sits in the cart's `pendingPaid`; the cart-level
     * `CartService.confirmCheck` settles it into `paid`.
     */
    suspend fun confirmCheck(paymentId: UUID, checkNumber: String?, principalId: UUID?): Payment

    /** Refund (part of) an original payment to the original method; records a linked REFUND row. */
    suspend fun refund(paymentId: UUID, amount: Money, reason: String?, principalId: UUID?): Payment

    /** Void an uncompleted/uncaptured payment. */
    suspend fun void(paymentId: UUID, reason: String?, principalId: UUID?): Payment

    /**
     * Refund (part of) a payment to the account's store credit instead of the original method —
     * records the REFUND_TO_ACCOUNT_CREDIT row and credits the account atomically (one transaction).
     */
    suspend fun refundToAccountCredit(paymentId: UUID, amount: Money, reason: String?, principalId: UUID?): Payment

    /**
     * Refund (part of) a payment by check (legacy `refundToCheck`) — records a REFUND_TO_CHECK row
     * carrying the [checkNumber]; no gateway call (the check is cut outside the system).
     */
    suspend fun refundToCheck(paymentId: UUID, amount: Money, checkNumber: String?, reason: String?, principalId: UUID?): Payment
}
