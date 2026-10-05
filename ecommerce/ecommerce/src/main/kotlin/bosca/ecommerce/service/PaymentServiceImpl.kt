package bosca.ecommerce.service

import bosca.db.transaction
import bosca.di.provide
import bosca.ecommerce.events.CartRefunded
import bosca.ecommerce.events.CartVoided
import bosca.ecommerce.events.PaymentConfirmed
import bosca.ecommerce.events.PaymentRefunded
import bosca.ecommerce.events.PaymentVoided
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.ChargePaymentInput
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentResult
import bosca.ecommerce.model.PaymentSubmitResult
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.model.SavedChargeInput
import bosca.ecommerce.repository.PaymentProviderRepository
import bosca.ecommerce.repository.PaymentRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/**
 * Payments. Each operation resolves the store's payment provider config to a [PaymentProcessor] by
 * `providerKey`, calls the gateway, and records the movement with its evidence (tokens only).
 * Refunds link to the original via the parent chain and accumulate `refundedAmount`;
 * refund-to-credit records the row AND credits the account in one transaction.
 */
@ServiceImplementation
class PaymentServiceImpl(
    private val paymentRepository: PaymentRepository,
    private val paymentProviderRepository: PaymentProviderRepository,
    private val storeService: StoreService,
    private val catalogService: CatalogService,
    private val accountService: AccountService,
    private val companyService: CompanyService,
    private val auditService: EcomAuditService,
) : PaymentService {

    override suspend fun get(id: UUID): Payment? = paymentRepository.get(id)

    override suspend fun getByAccount(accountId: UUID, offset: Int, limit: Int): List<Payment> =
        paymentRepository.getByAccount(accountId, offset, limit)

    override suspend fun getByDateRange(start: OffsetDateTime, end: OffsetDateTime, offset: Int, limit: Int): List<Payment> =
        paymentRepository.getByDateRange(start, end, offset, limit)

    override suspend fun getByCart(cartId: UUID, offset: Int, limit: Int): List<Payment> =
        paymentRepository.getByCart(cartId, offset, limit)

    override suspend fun getAllByCart(cartId: UUID): List<Payment> = paymentRepository.getAllByCart(cartId)

    override suspend fun confirmCheck(paymentId: UUID, checkNumber: String?, principalId: UUID?): Payment = transaction {
        val payment = paymentRepository.getForUpdate(paymentId) ?: error("payment $paymentId not found")
        check(payment.type == PaymentType.CHECK) { "payment $paymentId is not a check" }
        check(payment.complete) { "payment $paymentId is not complete" }
        check(!payment.confirmed) { "payment $paymentId is already confirmed" }
        val confirmed = paymentRepository.update(
            payment.copy(confirmed = true, checkConfirmed = OffsetDateTime.now(), checkNumber = checkNumber ?: payment.checkNumber),
        ) ?: error("payment $paymentId not found")
        audit(confirmed, "confirmed", principalId)
        confirmed.confirmedEvent().dispatch()
        confirmed
    }

    override suspend fun charge(input: ChargePaymentInput, principalId: UUID?): PaymentSubmitResult = transaction {
        val store = storeService.get(input.storeId) ?: error("store ${input.storeId} not found")
        // Prefer the snapshotted currency (cart.currency) over re-deriving the catalog's, so a charge is
        // denominated in the currency in force when the cart was priced.
        val currency = input.currency ?: (catalogService.get(store.catalogId)?.currency ?: error("catalog ${store.catalogId} not found"))
        val config = paymentProviderRepository.get(store.paymentProviderId)
            ?: error("payment provider ${store.paymentProviderId} not found")
        // Route by tender type (legacy parity): only CREDIT_CARD hits the gateway. Cash/check are
        // manual tenders recorded directly; account/company credit draw a stored balance. Every branch
        // produces a PaymentResult that records one Payment row the same way.
        val result = when (input.type) {
            PaymentType.CREDIT_CARD -> processor(config.providerKey).submit(config, input.amount, currency, input.token, input.creditCard, input.save, input.idempotencyKey)
            // Cash is in hand → complete + confirmed.
            PaymentType.CASH -> PaymentResult(complete = true, confirmed = true, status = "CASH")
            // Check is recorded complete but unconfirmed until it clears (legacy checkConfirmed gate).
            PaymentType.CHECK -> PaymentResult(complete = true, confirmed = false, status = "CHECK")
            PaymentType.ACCOUNT_CREDIT -> {
                val accountId = input.accountId ?: error("account credit requires an account on the cart")
                val account = accountService.get(accountId) ?: error("account $accountId not found")
                if (account.credit < input.amount) {
                    PaymentResult(complete = false, status = "FAILURE", error = "insufficient account credit")
                } else {
                    accountService.spendCredit(accountId, input.amount, "cart ${input.cartId ?: ""}", principalId)
                    PaymentResult(complete = true, confirmed = true, status = "ACCOUNT_CREDIT")
                }
            }
            PaymentType.COMPANY_CREDIT -> {
                val ccNumber = input.companyCreditNumber ?: error("company credit requires a credit number")
                if (companyService.redeemCredit(ccNumber, input.amount, input.accountId)) {
                    PaymentResult(complete = true, confirmed = true, status = "COMPANY_CREDIT")
                } else {
                    PaymentResult(complete = false, status = "FAILURE", error = "insufficient or invalid company credit")
                }
            }
        }
        val payment = record(
            Payment(
                transactionType = TransactionType.PAYMENT, type = input.type, providerId = config.id, storeId = store.id,
                accountId = input.accountId, customerId = input.customerId, cartId = input.cartId, amount = input.amount,
                currency = currency,
                email = input.email, checkNumber = input.checkNumber, companyCreditNumber = input.companyCreditNumber,
            ),
            result,
        )
        audit(payment, "charged", principalId)
        if (payment.complete) payment.confirmedEvent().dispatch()
        PaymentSubmitResult(payment, result.saved, result.transportFailure)
    }

    override suspend fun chargeSaved(input: SavedChargeInput, principalId: UUID?): PaymentSubmitResult = transaction {
        val store = storeService.get(input.storeId) ?: error("store ${input.storeId} not found")
        // Prefer the subscription's snapshotted currency over re-deriving the catalog's, so a renewal stays
        // denominated in the currency in force at signup even if the catalog's currency later changes.
        val currency = input.currency ?: (catalogService.get(store.catalogId)?.currency ?: error("catalog ${store.catalogId} not found"))
        val config = paymentProviderRepository.get(store.paymentProviderId)
            ?: error("payment provider ${store.paymentProviderId} not found")
        val result = processor(config.providerKey).charge(config, input.saved, input.amount, currency, input.idempotencyKey)
        val payment = record(
            Payment(
                transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = config.id, storeId = store.id,
                accountId = input.accountId, customerId = input.customerId, subscriptionId = input.subscriptionId, amount = input.amount,
                currency = currency,
            ),
            result,
        )
        audit(payment, "charged", principalId)
        if (payment.complete) payment.confirmedEvent().dispatch()
        PaymentSubmitResult(payment, result.saved, result.transportFailure)
    }

    override suspend fun refund(paymentId: UUID, amount: Money, reason: String?, principalId: UUID?): Payment = transaction {
        val original = paymentRepository.getForUpdate(paymentId) ?: error("payment $paymentId not found")
        requireRefundable(original, amount)
        val config = paymentProviderRepository.get(original.providerId) ?: error("payment provider not found")
        val result = processor(config.providerKey).refund(config, original.providerTransactionId, amount, original.currency)
        check(result.complete) { "refund failed: ${result.error ?: result.message}" }
        val refund = recordRefund(original, TransactionType.REFUND, original.type, amount, reason, result)
        applyRefundToOriginal(original, amount, reason)
        audit(refund, "refunded", principalId)
        dispatchRefunded(refund, original, amount)
        refund
    }

    override suspend fun void(paymentId: UUID, reason: String?, principalId: UUID?): Payment = transaction {
        val original = paymentRepository.getForUpdate(paymentId) ?: error("payment $paymentId not found")
        check(original.voided == null) { "payment $paymentId is already voided" }
        check(original.complete) { "payment $paymentId is not complete" }
        val voided = paymentRepository.update(
            original.copy(voided = OffsetDateTime.now(), voidedReason = reason, complete = false, confirmed = false),
        ) ?: error("payment $paymentId not found")
        audit(voided, "voided", principalId)
        PaymentVoided(paymentId = voided.id, storeId = voided.storeId).dispatch()
        original.cartId?.let { CartVoided(cartId = it, storeId = original.storeId).dispatch() }
        voided
    }

    override suspend fun refundToAccountCredit(paymentId: UUID, amount: Money, reason: String?, principalId: UUID?): Payment =
        transaction {
            val original = paymentRepository.getForUpdate(paymentId) ?: error("payment $paymentId not found")
            val accountId = original.accountId ?: error("payment $paymentId has no account to credit")
            requireRefundable(original, amount)
            // Record the credit-refund and credit the account in the SAME transaction (atomic).
            val refund = recordRefund(original, TransactionType.REFUND_TO_ACCOUNT_CREDIT, PaymentType.ACCOUNT_CREDIT, amount, reason, null)
            accountService.addCredit(accountId, amount, reason ?: "refund of payment $paymentId", principalId)
            applyRefundToOriginal(original, amount, reason)
            audit(refund, "refunded_to_credit", principalId)
            dispatchRefunded(refund, original, amount)
            refund
        }

    override suspend fun refundToCheck(
        paymentId: UUID,
        amount: Money,
        checkNumber: String?,
        reason: String?,
        principalId: UUID?,
    ): Payment = transaction {
        val original = paymentRepository.getForUpdate(paymentId) ?: error("payment $paymentId not found")
        requireRefundable(original, amount)
        // No gateway call — the check is cut outside the system; record the REFUND_TO_CHECK row and
        // accumulate the original's refunded amount (mirrors refundToAccountCredit, sans the credit).
        val refund = recordRefund(original, TransactionType.REFUND_TO_CHECK, PaymentType.CHECK, amount, reason, null)
            .let { if (checkNumber != null) paymentRepository.update(it.copy(checkNumber = checkNumber)) ?: it else it }
        applyRefundToOriginal(original, amount, reason)
        audit(refund, "refunded_to_check", principalId)
        dispatchRefunded(refund, original, amount)
        refund
    }

    private fun requireRefundable(original: Payment, amount: Money) {
        require(amount.isPositive) { "refund amount must be positive" }
        check(original.voided == null) { "payment ${original.id} is voided" }
        check(original.complete) { "payment ${original.id} is not complete" }
        val refundable = original.amount - original.refundedAmount - original.nonRefundableAmount
        check(amount <= refundable) { "refund $amount exceeds refundable $refundable on payment ${original.id}" }
    }

    private suspend fun recordRefund(
        original: Payment,
        transactionType: TransactionType,
        type: PaymentType,
        amount: Money,
        reason: String?,
        result: PaymentResult?,
    ): Payment = paymentRepository.add(
        Payment(
            transactionType = transactionType, type = type, providerId = original.providerId, storeId = original.storeId,
            accountId = original.accountId, customerId = original.customerId, cartId = original.cartId, parentId = original.id,
            amount = amount, currency = original.currency, complete = true, confirmed = true, refunded = OffsetDateTime.now(), refundReason = reason,
            parentProviderId = original.providerProviderId, parentProviderTransactionId = original.providerTransactionId,
            providerProviderId = result?.providerId, providerStatus = result?.status, providerTransactionId = result?.transactionId,
        ),
    )

    private suspend fun applyRefundToOriginal(original: Payment, amount: Money, reason: String?) {
        paymentRepository.update(
            original.copy(refundedAmount = original.refundedAmount + amount, refunded = OffsetDateTime.now(), refundReason = reason),
        )
    }

    /** Persists a charge, mapping the gateway result onto the payment's provider-evidence columns. */
    private suspend fun record(payment: Payment, result: PaymentResult): Payment = paymentRepository.add(
        payment.copy(
            complete = result.complete,
            confirmed = result.confirmed,
            providerProviderId = result.providerId,
            providerStatus = result.status,
            providerTransactionId = result.transactionId,
            providerAvs = result.avs,
            providerType = result.type,
            providerMessage = result.message,
            providerError = result.error,
        ),
    )

    /** Resolves the gateway by its `providerKey` directly — providers register under `name = key`. */
    private suspend fun processor(providerKey: String): PaymentProcessor = provide(name = providerKey)

    private fun Payment.confirmedEvent(): PaymentConfirmed =
        PaymentConfirmed(paymentId = id, storeId = storeId, amount = amount, cartId = cartId, subscriptionId = subscriptionId)

    /** Emits the payment-level refund event and, when the payment was cart-bound, the cart-level one. */
    private suspend fun dispatchRefunded(refund: Payment, original: Payment, amount: Money) {
        PaymentRefunded(
            paymentId = refund.id, storeId = refund.storeId, amount = amount, parentId = original.id, cartId = original.cartId,
        ).dispatch()
        original.cartId?.let { CartRefunded(cartId = it, storeId = original.storeId).dispatch() }
    }

    private suspend fun audit(payment: Payment, action: String, principalId: UUID?) {
        auditService.record(
            entityType = "payment",
            entityId = payment.id,
            action = action,
            serializer = Payment.serializer(),
            after = payment,
            principalId = principalId,
            storeId = payment.storeId,
        )
    }
}
