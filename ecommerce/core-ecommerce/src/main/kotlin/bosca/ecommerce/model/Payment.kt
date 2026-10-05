package bosca.ecommerce.model

import bosca.db.annotation.ColumnName
import bosca.graphql.annotations.BatchKey
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A payment record (`ecom.payments`): one row per money movement — a charge, a refund (linked to its
 * original via [parentId]), a void, or a refund-to-account-credit. The `provider*` columns hold the
 * gateway's evidence (tokens/ids only — never a card PAN). [refundedAmount] accumulates on the
 * original as refunds are issued; [nonRefundableAmount] caps what can't be refunded.
 */
@BatchKey("id")
@Serializable
data class Payment(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("transaction_type")
    val transactionType: TransactionType,
    val type: PaymentType,
    @Contextual
    @ColumnName("provider_id")
    val providerId: UUID,
    @Contextual
    @ColumnName("store_id")
    val storeId: UUID,
    @Contextual
    @ColumnName("account_id")
    val accountId: UUID? = null,
    @Contextual
    @ColumnName("customer_id")
    val customerId: UUID? = null,
    @Contextual
    @ColumnName("cart_id")
    val cartId: UUID? = null,
    @Contextual
    @ColumnName("subscription_id")
    val subscriptionId: UUID? = null,
    @Contextual
    @ColumnName("parent_id")
    val parentId: UUID? = null,
    val amount: Money = Money.ZERO,
    /** The ISO-4217 currency this payment moved (stamped from the store; refunds inherit the original's). */
    val currency: String = "USD",
    @ColumnName("non_refundable_amount")
    val nonRefundableAmount: Money = Money.ZERO,
    @ColumnName("refunded_amount")
    val refundedAmount: Money = Money.ZERO,
    @ColumnName("company_credit_number")
    val companyCreditNumber: String? = null,
    @ColumnName("check_number")
    val checkNumber: String? = null,
    @Contextual
    @ColumnName("check_confirmed")
    val checkConfirmed: OffsetDateTime? = null,
    val complete: Boolean = false,
    val confirmed: Boolean = false,
    @Contextual
    val voided: OffsetDateTime? = null,
    @ColumnName("voided_reason")
    val voidedReason: String? = null,
    @Contextual
    val refunded: OffsetDateTime? = null,
    @ColumnName("refund_reason")
    val refundReason: String? = null,
    val note: String? = null,
    @ColumnName("email_receipt")
    val emailReceipt: Boolean = true,
    val email: String? = null,
    @ColumnName("provider_provider_id")
    val providerProviderId: String? = null,
    @ColumnName("provider_status")
    val providerStatus: String? = null,
    @ColumnName("provider_transaction_id")
    val providerTransactionId: String? = null,
    @ColumnName("provider_avs")
    val providerAvs: String? = null,
    @ColumnName("provider_type")
    val providerType: String? = null,
    @ColumnName("provider_message")
    val providerMessage: String? = null,
    @ColumnName("provider_error")
    val providerError: String? = null,
    @ColumnName("parent_provider_id")
    val parentProviderId: String? = null,
    @ColumnName("parent_provider_transaction_id")
    val parentProviderTransactionId: String? = null,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual
    val modified: OffsetDateTime = OffsetDateTime.now(),
)
