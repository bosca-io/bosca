package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Payment
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID

/** Persistence for `ecom.payments` (append-mostly; [update] touches the refund/void lifecycle fields). */
@Repository
interface PaymentRepository {

    @Query("select * from ecom.payments where id = :id")
    suspend fun get(id: UUID): Payment?

    /** Row-locked single read for the refund/void lifecycle — serializes concurrent mutations on one payment. */
    @Query("select * from ecom.payments where id = :id for update")
    suspend fun getForUpdate(id: UUID): Payment?

    /** A page of a cart's payments, oldest first — the bounded resolver path ([Cart.payments]). */
    @Query("select * from ecom.payments where cart_id = :cartId order by created offset :offset limit :limit")
    suspend fun getByCart(cartId: UUID, offset: Int, limit: Int): List<Payment>

    /** ALL of a cart's payments — for settlement/refund math that must see every tender (never page this). */
    @Query("select * from ecom.payments where cart_id = :cartId order by created")
    suspend fun getAllByCart(cartId: UUID): List<Payment>

    @Query("select * from ecom.payments where account_id = :accountId order by created desc offset :offset limit :limit")
    suspend fun getByAccount(accountId: UUID, offset: Int, limit: Int): List<Payment>

    /** Payments recorded in the `[start, end)` window, newest first (legacy date-range reporting query). */
    @Query("select * from ecom.payments where created >= :start and created < :end order by created desc offset :offset limit :limit")
    suspend fun getByDateRange(start: OffsetDateTime, end: OffsetDateTime, offset: Int, limit: Int): List<Payment>

    @Query(
        """
        insert into ecom.payments
            (transaction_type, type, provider_id, store_id, account_id, customer_id, cart_id, subscription_id, parent_id,
             amount, currency, non_refundable_amount, refunded_amount, company_credit_number, check_number, check_confirmed,
             complete, confirmed, voided, voided_reason, refunded, refund_reason, note, email_receipt, email,
             provider_provider_id, provider_status, provider_transaction_id, provider_avs, provider_type, provider_message,
             provider_error, parent_provider_id, parent_provider_transaction_id)
        values
            ((:transactionType)::ecom.transaction_type, (:type)::ecom.payment_type, :providerId, :storeId, :accountId, :customerId, :cartId, :subscriptionId, :parentId,
             :amount, :currency, :nonRefundableAmount, :refundedAmount, :companyCreditNumber, :checkNumber, :checkConfirmed,
             :complete, :confirmed, :voided, :voidedReason, :refunded, :refundReason, :note, :emailReceipt, :email,
             :providerProviderId, :providerStatus, :providerTransactionId, :providerAvs, :providerType, :providerMessage,
             :providerError, :parentProviderId, :parentProviderTransactionId)
        returning *
        """,
    )
    suspend fun add(payment: Payment): Payment

    @Query(
        """
        update ecom.payments
           set refunded_amount = :refundedAmount, refunded = :refunded, refund_reason = :refundReason,
               voided = :voided, voided_reason = :voidedReason, complete = :complete, confirmed = :confirmed, modified = now()
         where id = :id
        returning *
        """,
    )
    suspend fun update(payment: Payment): Payment?
}
