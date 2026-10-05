@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.ecommerce.model.Account
import bosca.ecommerce.model.AccountType
import bosca.ecommerce.model.ChargePaymentInput
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentProvider
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.Store
import bosca.ecommerce.model.StoreType
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.model.SavedChargeInput
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.ecommerce.repository.PaymentProviderRepository
import bosca.ecommerce.repository.PaymentRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**payment orchestration — charge, refund chain, refund-to-credit atomicity, save-then-recharge, void. */
@OptIn(ExperimentalUuidApi::class)
class PaymentServiceImplTest {

    private val paymentRepository = mockk<PaymentRepository>(relaxUnitFun = true)
    private val paymentProviderRepository = mockk<PaymentProviderRepository>()
    private val storeService = mockk<StoreService>()
    private val catalogService = mockk<CatalogService>()
    private val accountService = mockk<AccountService>(relaxed = true)
    private val companyService = mockk<CompanyService>(relaxed = true)
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: PaymentServiceImpl

    private val storeId = UUID.random()
    private val providerId = UUID.random()
    private val accountId = UUID.random()
    private val companyId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "test", singleton = true) { TestPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        // The refund/void lifecycle now loads via getForUpdate (row lock); against a mock it returns the same row as get.
        coEvery { paymentRepository.getForUpdate(any()) } coAnswers { paymentRepository.get(firstArg<UUID>()) }
        coEvery { storeService.get(storeId) } returns Store(
            id = storeId, identifier = "s", name = "S", companyId = companyId, catalogId = UUID.random(),
            type = StoreType.VIRTUAL, paymentProviderId = providerId, shippingCatalogProductId = UUID.random(),
        )
        coEvery { paymentProviderRepository.get(providerId) } returns PaymentProvider(
            id = providerId, companyId = companyId, name = "Test", providerKey = "test",
        )
        coEvery { catalogService.get(any()) } returns bosca.ecommerce.model.Catalog(id = UUID.random(), companyId = companyId, key = "c", name = "C")
        service = PaymentServiceImpl(paymentRepository, paymentProviderRepository, storeService, catalogService, accountService, companyService, auditService)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun captureAdd(): CapturingSlot<Payment> {
        val slot = slot<Payment>()
        coEvery { paymentRepository.add(capture(slot)) } answers { slot.captured.copy(id = UUID.random()) }
        return slot
    }

    @Test
    fun `charge records a completed payment with provider evidence`() = runTest {
        val added = captureAdd()
        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("20.00"), token = "tok"), principalId = null)
        assertEquals(TransactionType.PAYMENT, added.captured.transactionType)
        assertEquals(Money.of("20.00"), added.captured.amount)
        assertTrue(added.captured.complete)
        assertNotNull(added.captured.providerTransactionId)
        assertTrue(result.payment.complete)
    }

    @Test
    fun `charge with save returns a reusable method`() = runTest {
        captureAdd()
        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("9.99"), token = "tok", save = true), principalId = null)
        assertNotNull(result.saved)
        assertEquals("test", result.saved?.providerKey)
    }

    @Test
    fun `refund records a linked refund and accumulates on the original`() = runTest {
        val originalId = UUID.random()
        val original = Payment(id = originalId, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true, providerTransactionId = "tx-1")
        coEvery { paymentRepository.get(originalId) } returns original
        val added = captureAdd()
        val updated = slot<Payment>()
        coEvery { paymentRepository.update(capture(updated)) } answers { updated.captured }

        service.refund(originalId, Money.of("5.00"), reason = "partial", principalId = null)

        assertEquals(TransactionType.REFUND, added.captured.transactionType)
        assertEquals(originalId, added.captured.parentId)
        assertEquals(Money.of("5.00"), added.captured.amount)
        assertEquals("tx-1", added.captured.parentProviderTransactionId)
        assertEquals(Money.of("5.00"), updated.captured.refundedAmount)
    }

    @Test
    fun `refund exceeding the refundable balance fails`() = runTest {
        val originalId = UUID.random()
        coEvery { paymentRepository.get(originalId) } returns Payment(id = originalId, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), refundedAmount = Money.of("18.00"), complete = true)
        assertFailsWith<IllegalStateException> { service.refund(originalId, Money.of("5.00"), null, null) }
    }

    @Test
    fun `refund to account credit records the refund and credits the account atomically`() = runTest {
        val originalId = UUID.random()
        coEvery { paymentRepository.get(originalId) } returns Payment(id = originalId, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, accountId = accountId, amount = Money.of("20.00"), complete = true)
        val added = captureAdd()
        coEvery { paymentRepository.update(any()) } answers { firstArg() }
        coEvery { accountService.addCredit(any(), any(), any(), any()) } returns mockk<Account>()

        service.refundToAccountCredit(originalId, Money.of("8.00"), reason = "goodwill", principalId = null)

        assertEquals(TransactionType.REFUND_TO_ACCOUNT_CREDIT, added.captured.transactionType)
        coVerify(exactly = 1) { accountService.addCredit(eq(accountId), eq(Money.of("8.00")), any(), any()) }
    }

    @Test
    fun `save then recharge reuses the saved method`() = runTest {
        captureAdd()
        val saved = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("30.00"), token = "tok", save = true), principalId = null).saved
        assertNotNull(saved)

        val added = captureAdd()
        val payment = service.chargeSaved(SavedChargeInput(storeId = storeId, saved = requireNotNull(saved), amount = Money.of("30.00")), principalId = null)

        assertEquals(TransactionType.PAYMENT, added.captured.transactionType)
        assertTrue(payment.payment.complete)
    }

    @Test
    fun `cash is recorded complete and confirmed without a gateway`() = runTest {
        val added = captureAdd()
        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.CASH), principalId = null)
        assertEquals(PaymentType.CASH, added.captured.type)
        assertTrue(added.captured.complete)
        assertTrue(added.captured.confirmed)
        assertTrue(result.payment.complete)
    }

    @Test
    fun `charge uses the snapshotted currency and does not load the catalog`() = runTest {
        val added = captureAdd()
        // currency supplied on the input -> used verbatim; the catalog is not consulted.
        service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.CASH, currency = "EUR"), principalId = null)
        assertEquals("EUR", added.captured.currency)
        coVerify(exactly = 0) { catalogService.get(any()) }
    }

    @Test
    fun `chargeSaved uses the snapshotted currency and does not load the catalog`() = runTest {
        val added = captureAdd()
        service.chargeSaved(SavedChargeInput(storeId = storeId, saved = SavedPaymentMethod("test", "tok"), amount = Money.of("10.00"), currency = "EUR"), principalId = null)
        assertEquals("EUR", added.captured.currency)
        coVerify(exactly = 0) { catalogService.get(any()) }
    }

    @Test
    fun `check is recorded complete but unconfirmed and keeps the check number`() = runTest {
        val added = captureAdd()
        service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.CHECK, checkNumber = "4021"), principalId = null)
        assertTrue(added.captured.complete)
        assertTrue(!added.captured.confirmed)
        assertEquals("4021", added.captured.checkNumber)
    }

    @Test
    fun `account credit with sufficient balance spends and completes`() = runTest {
        coEvery { accountService.get(accountId) } returns Account(id = accountId, companyId = companyId, type = AccountType.CONSUMER, credit = Money.of("50.00"))
        val added = captureAdd()

        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.ACCOUNT_CREDIT, accountId = accountId), principalId = null)

        assertTrue(result.payment.complete)
        assertTrue(added.captured.confirmed)
        coVerify(exactly = 1) { accountService.spendCredit(eq(accountId), eq(Money.of("10.00")), any(), any()) }
    }

    @Test
    fun `account credit with insufficient balance fails and does not spend`() = runTest {
        coEvery { accountService.get(accountId) } returns Account(id = accountId, companyId = companyId, type = AccountType.CONSUMER, credit = Money.of("5.00"))
        val added = captureAdd()

        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.ACCOUNT_CREDIT, accountId = accountId), principalId = null)

        assertTrue(!result.payment.complete)
        assertTrue(!added.captured.complete)
        coVerify(exactly = 0) { accountService.spendCredit(any(), any(), any(), any()) }
    }

    @Test
    fun `company credit redeems and completes when the instrument covers it`() = runTest {
        coEvery { companyService.redeemCredit("GC-1", Money.of("10.00"), accountId) } returns true
        val added = captureAdd()

        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.COMPANY_CREDIT, accountId = accountId, companyCreditNumber = "GC-1"), principalId = null)

        assertTrue(result.payment.complete)
        assertEquals("GC-1", added.captured.companyCreditNumber)
        coVerify(exactly = 1) { companyService.redeemCredit("GC-1", Money.of("10.00"), accountId) }
    }

    @Test
    fun `company credit fails when the instrument cannot cover it`() = runTest {
        coEvery { companyService.redeemCredit(any(), any(), any()) } returns false
        val added = captureAdd()

        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.COMPANY_CREDIT, accountId = accountId, companyCreditNumber = "GC-2"), principalId = null)

        assertTrue(!result.payment.complete)
        assertTrue(!added.captured.complete)
    }

    @Test
    fun `void marks a payment voided and incomplete`() = runTest {
        val originalId = UUID.random()
        coEvery { paymentRepository.get(originalId) } returns Payment(id = originalId, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        val updated = slot<Payment>()
        coEvery { paymentRepository.update(capture(updated)) } answers { updated.captured }

        service.void(originalId, reason = "duplicate", principalId = null)

        assertNotNull(updated.captured.voided)
        assertTrue(!updated.captured.complete)
    }

    // --- read delegators ---

    @Test
    fun `read delegators forward to the repository`() = runTest {
        val id = UUID.random()
        val payment = Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CASH, providerId = providerId, storeId = storeId, amount = Money.of("1.00"))
        coEvery { paymentRepository.get(id) } returns payment
        coEvery { paymentRepository.getByAccount(accountId, 2, 9) } returns listOf(payment)
        val cartId = UUID.random()
        coEvery { paymentRepository.getByCart(cartId, 0, 25) } returns listOf(payment)
        coEvery { paymentRepository.getAllByCart(cartId) } returns listOf(payment, payment)
        val start = OffsetDateTime.now().minusDays(7)
        val end = OffsetDateTime.now()
        coEvery { paymentRepository.getByDateRange(start, end, 0, 50) } returns listOf(payment, payment)

        assertEquals(id, service.get(id)?.id)
        assertEquals(1, service.getByAccount(accountId, 2, 9).size)
        assertEquals(1, service.getByCart(cartId, 0, 25).size)
        assertEquals(2, service.getAllByCart(cartId).size)
        assertEquals(2, service.getByDateRange(start, end, 0, 50).size)
    }

    @Test
    fun `charge and chargeSaved fail when the store's catalog is missing`() = runTest {
        coEvery { catalogService.get(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("1.00"), token = "tok"), principalId = null)
        }
        assertFailsWith<IllegalStateException> {
            service.chargeSaved(SavedChargeInput(storeId = storeId, saved = SavedPaymentMethod("test", "t"), amount = Money.of("1.00")), principalId = null)
        }
    }

    // --- confirmCheck ---

    @Test
    fun `confirmCheck confirms a complete unconfirmed check and keeps the existing number when none is sent`() = runTest {
        val id = UUID.random()
        val check = Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CHECK, providerId = providerId, storeId = storeId, amount = Money.of("12.00"), complete = true, confirmed = false, checkNumber = "OLD-1")
        coEvery { paymentRepository.get(id) } returns check
        val updated = slot<Payment>()
        coEvery { paymentRepository.update(capture(updated)) } answers { updated.captured }

        val result = service.confirmCheck(id, checkNumber = null, principalId = null)

        assertTrue(result.confirmed)
        assertNotNull(updated.captured.checkConfirmed)
        assertEquals("OLD-1", updated.captured.checkNumber) // null arg -> keep existing
    }

    @Test
    fun `confirmCheck overrides the check number when one is supplied`() = runTest {
        val id = UUID.random()
        val check = Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CHECK, providerId = providerId, storeId = storeId, amount = Money.of("12.00"), complete = true, confirmed = false, checkNumber = "OLD-1")
        coEvery { paymentRepository.get(id) } returns check
        val updated = slot<Payment>()
        coEvery { paymentRepository.update(capture(updated)) } answers { updated.captured }

        service.confirmCheck(id, checkNumber = "NEW-2", principalId = null)

        assertEquals("NEW-2", updated.captured.checkNumber)
    }

    @Test
    fun `confirmCheck throws when the payment does not exist`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns null
        assertFailsWith<IllegalStateException> { service.confirmCheck(id, null, null) }
    }

    @Test
    fun `confirmCheck rejects a non-check payment`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CASH, providerId = providerId, storeId = storeId, amount = Money.of("12.00"), complete = true)
        assertFailsWith<IllegalStateException> { service.confirmCheck(id, null, null) }
    }

    @Test
    fun `confirmCheck rejects an incomplete check`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CHECK, providerId = providerId, storeId = storeId, amount = Money.of("12.00"), complete = false)
        assertFailsWith<IllegalStateException> { service.confirmCheck(id, null, null) }
    }

    @Test
    fun `confirmCheck rejects an already-confirmed check`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CHECK, providerId = providerId, storeId = storeId, amount = Money.of("12.00"), complete = true, confirmed = true)
        assertFailsWith<IllegalStateException> { service.confirmCheck(id, null, null) }
    }

    @Test
    fun `confirmCheck throws when the update returns null`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CHECK, providerId = providerId, storeId = storeId, amount = Money.of("12.00"), complete = true, confirmed = false)
        coEvery { paymentRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.confirmCheck(id, null, null) }
    }

    // --- charge error / branch coverage ---

    @Test
    fun `charge throws when the store is not found`() = runTest {
        coEvery { storeService.get(storeId) } returns null
        assertFailsWith<IllegalStateException> {
            service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("1.00")), principalId = null)
        }
    }

    @Test
    fun `charge throws when the payment provider config is missing`() = runTest {
        coEvery { paymentProviderRepository.get(providerId) } returns null
        assertFailsWith<IllegalStateException> {
            service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("1.00")), principalId = null)
        }
    }

    @Test
    fun `account credit charge requires an account on the cart`() = runTest {
        assertFailsWith<IllegalStateException> {
            service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.ACCOUNT_CREDIT, accountId = null), principalId = null)
        }
    }

    @Test
    fun `account credit charge throws when the account is not found`() = runTest {
        coEvery { accountService.get(accountId) } returns null
        assertFailsWith<IllegalStateException> {
            service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.ACCOUNT_CREDIT, accountId = accountId), principalId = null)
        }
    }

    @Test
    fun `company credit charge requires a credit number`() = runTest {
        assertFailsWith<IllegalStateException> {
            service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.COMPANY_CREDIT, companyCreditNumber = null), principalId = null)
        }
    }

    @Test
    fun `an incomplete charge is recorded without dispatching the confirmed event`() = runTest {
        val recorder = RecordingPubSub()
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "test", singleton = true) { TestPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        coEvery { companyService.redeemCredit(any(), any(), any()) } returns false
        captureAdd()

        val result = service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.COMPANY_CREDIT, companyCreditNumber = "GC-X"), principalId = null)

        assertTrue(!result.payment.complete)
        assertTrue(recorder.published.none { it.first == bosca.ecommerce.events.PAYMENT_CONFIRMED_CHANNEL })
    }

    @Test
    fun `a completed charge dispatches the confirmed event`() = runTest {
        val recorder = RecordingPubSub()
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "test", singleton = true) { TestPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        captureAdd()

        service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.CASH), principalId = null)

        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.PAYMENT_CONFIRMED_CHANNEL })
    }

    // --- chargeSaved error paths ---

    @Test
    fun `chargeSaved throws when the store is not found`() = runTest {
        coEvery { storeService.get(storeId) } returns null
        assertFailsWith<IllegalStateException> {
            service.chargeSaved(SavedChargeInput(storeId = storeId, saved = SavedPaymentMethod("test", "tok"), amount = Money.of("1.00")), principalId = null)
        }
    }

    @Test
    fun `chargeSaved throws when the provider config is missing`() = runTest {
        coEvery { paymentProviderRepository.get(providerId) } returns null
        assertFailsWith<IllegalStateException> {
            service.chargeSaved(SavedChargeInput(storeId = storeId, saved = SavedPaymentMethod("test", "tok"), amount = Money.of("1.00")), principalId = null)
        }
    }

    // --- refund error paths ---

    @Test
    fun `refund throws when the original payment does not exist`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns null
        assertFailsWith<IllegalStateException> { service.refund(id, Money.of("1.00"), null, null) }
    }

    @Test
    fun `refund rejects a non-positive amount`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        assertFailsWith<IllegalArgumentException> { service.refund(id, Money.ZERO, null, null) }
    }

    @Test
    fun `refund rejects a voided payment`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = false, voided = OffsetDateTime.now())
        assertFailsWith<IllegalStateException> { service.refund(id, Money.of("5.00"), null, null) }
    }

    @Test
    fun `refund rejects an incomplete payment`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = false)
        assertFailsWith<IllegalStateException> { service.refund(id, Money.of("5.00"), null, null) }
    }

    @Test
    fun `void rejects an already-voided payment`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = false, voided = OffsetDateTime.now())
        assertFailsWith<IllegalStateException> { service.void(id, null, null) }
    }

    @Test
    fun `void rejects an incomplete payment`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = false)
        assertFailsWith<IllegalStateException> { service.void(id, null, null) }
    }

    @Test
    fun `refund throws when the provider config is missing`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        coEvery { paymentProviderRepository.get(providerId) } returns null
        assertFailsWith<IllegalStateException> { service.refund(id, Money.of("5.00"), null, null) }
    }

    @Test
    fun `refund fails when the gateway declines`() = runTest {
        // Register a declining processor under its own key and point the provider config at it.
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "declining", singleton = true) { DecliningPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        val id = UUID.random()
        coEvery { paymentProviderRepository.get(providerId) } returns PaymentProvider(id = providerId, companyId = companyId, name = "Decl", providerKey = "declining")
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true, providerTransactionId = "tx")

        assertFailsWith<IllegalStateException> { service.refund(id, Money.of("5.00"), null, null) }
        coVerify(exactly = 0) { paymentRepository.add(any()) }
    }

    @Test
    fun `refund emits the payment-refunded and cart-refunded events for a cart-bound payment`() = runTest {
        val recorder = RecordingPubSub()
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "test", singleton = true) { TestPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        val id = UUID.random()
        val cartId = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, cartId = cartId, amount = Money.of("20.00"), complete = true, providerTransactionId = "tx")
        captureAdd()
        coEvery { paymentRepository.update(any()) } answers { firstArg() }

        service.refund(id, Money.of("5.00"), null, null)

        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.PAYMENT_REFUNDED_CHANNEL })
        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.CART_REFUNDED_CHANNEL })
    }

    // --- void error / cart-bound path ---

    @Test
    fun `void throws when the payment does not exist`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns null
        assertFailsWith<IllegalStateException> { service.void(id, null, null) }
    }

    @Test
    fun `void throws when the update returns null`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        coEvery { paymentRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.void(id, null, null) }
    }

    @Test
    fun `void of a cart-bound payment also emits the cart-voided event`() = runTest {
        val recorder = RecordingPubSub()
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "test", singleton = true) { TestPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        val id = UUID.random()
        val cartId = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, cartId = cartId, amount = Money.of("20.00"), complete = true)
        coEvery { paymentRepository.update(any()) } answers { firstArg() }

        service.void(id, reason = "dup", principalId = null)

        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.PAYMENT_VOIDED_CHANNEL })
        assertTrue(recorder.published.any { it.first == bosca.ecommerce.events.CART_VOIDED_CHANNEL })
    }

    // --- refundToAccountCredit error path ---

    @Test
    fun `refundToAccountCredit throws when the original does not exist`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns null
        assertFailsWith<IllegalStateException> { service.refundToAccountCredit(id, Money.of("1.00"), null, null) }
    }

    @Test
    fun `refundToAccountCredit throws when the payment has no account to credit`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, accountId = null, amount = Money.of("20.00"), complete = true)
        assertFailsWith<IllegalStateException> { service.refundToAccountCredit(id, Money.of("1.00"), null, null) }
    }

    // --- refundToCheck (untested method) ---

    @Test
    fun `refundToCheck records a check refund with the check number and accumulates on the original`() = runTest {
        val id = UUID.random()
        val original = Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        coEvery { paymentRepository.get(id) } returns original
        val added = captureAdd()
        val updates = mutableListOf<Payment>()
        coEvery { paymentRepository.update(capture(updates)) } answers { firstArg() }

        service.refundToCheck(id, Money.of("6.00"), checkNumber = "CK-7", reason = "rk", principalId = null)

        assertEquals(TransactionType.REFUND_TO_CHECK, added.captured.transactionType)
        assertEquals(PaymentType.CHECK, added.captured.type)
        // The refund row is updated with the check number, and the original accumulates the refunded amount.
        assertTrue(updates.any { it.checkNumber == "CK-7" })
        assertTrue(updates.any { it.refundedAmount == Money.of("6.00") })
    }

    @Test
    fun `refundToCheck without a check number records the refund row as-is`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        val added = captureAdd()
        val updates = mutableListOf<Payment>()
        coEvery { paymentRepository.update(capture(updates)) } answers { firstArg() }

        service.refundToCheck(id, Money.of("6.00"), checkNumber = null, reason = null, principalId = null)

        assertEquals(TransactionType.REFUND_TO_CHECK, added.captured.transactionType)
        // No check-number update of the refund row; only the original-accumulation update runs.
        assertTrue(updates.none { it.transactionType == TransactionType.REFUND_TO_CHECK && it.checkNumber != null })
        assertTrue(updates.any { it.refundedAmount == Money.of("6.00") })
    }

    @Test
    fun `refundToCheck throws when the original does not exist`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns null
        assertFailsWith<IllegalStateException> { service.refundToCheck(id, Money.of("1.00"), null, null, null) }
    }

    @Test
    fun `refundToCheck rejects an amount exceeding the refundable balance`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), nonRefundableAmount = Money.of("18.00"), complete = true)
        assertFailsWith<IllegalStateException> { service.refundToCheck(id, Money.of("5.00"), null, null, null) }
    }

    @Test
    fun `account credit charge with a cart records the cart in the credit memo`() = runTest {
        coEvery { accountService.get(accountId) } returns Account(id = accountId, companyId = companyId, type = AccountType.CONSUMER, credit = Money.of("50.00"))
        val cartId = UUID.random()
        captureAdd()

        service.charge(ChargePaymentInput(storeId = storeId, amount = Money.of("10.00"), type = PaymentType.ACCOUNT_CREDIT, accountId = accountId, cartId = cartId), principalId = null)

        coVerify(exactly = 1) { accountService.spendCredit(eq(accountId), eq(Money.of("10.00")), match { it.contains(cartId.toString()) }, any()) }
    }

    @Test
    fun `chargeSaved does not dispatch the confirmed event when the gateway declines`() = runTest {
        val recorder = RecordingPubSub()
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "declining", singleton = true) { DecliningPaymentProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { recorder }
        coEvery { paymentProviderRepository.get(providerId) } returns PaymentProvider(id = providerId, companyId = companyId, name = "Decl", providerKey = "declining")
        captureAdd()

        val payment = service.chargeSaved(SavedChargeInput(storeId = storeId, saved = SavedPaymentMethod("declining", "t"), amount = Money.of("5.00")), principalId = null)

        assertTrue(!payment.payment.complete)
        assertTrue(recorder.published.none { it.first == bosca.ecommerce.events.PAYMENT_CONFIRMED_CHANNEL })
    }

    @Test
    fun `refund failure message falls back to the result message when there is no error`() = runTest {
        ProviderRegistry.clear()
        provides<PaymentProcessor>(name = "msg-decline", singleton = true) { MessageOnlyDecliningProcessor() }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        val id = UUID.random()
        coEvery { paymentProviderRepository.get(providerId) } returns PaymentProvider(id = providerId, companyId = companyId, name = "M", providerKey = "msg-decline")
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true, providerTransactionId = "tx")

        val ex = assertFailsWith<IllegalStateException> { service.refund(id, Money.of("5.00"), null, null) }
        assertTrue(ex.message?.contains("soft decline") == true)
    }

    @Test
    fun `refundToAccountCredit uses a default reason when none is given`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, accountId = accountId, amount = Money.of("20.00"), complete = true)
        captureAdd()
        coEvery { paymentRepository.update(any()) } answers { firstArg() }
        coEvery { accountService.addCredit(any(), any(), any(), any()) } returns mockk<Account>()

        service.refundToAccountCredit(id, Money.of("8.00"), reason = null, principalId = null)

        coVerify(exactly = 1) { accountService.addCredit(eq(accountId), eq(Money.of("8.00")), match { it.contains("refund of payment") }, any()) }
    }

    @Test
    fun `refundToCheck keeps the recorded refund when the check-number update returns nothing`() = runTest {
        val id = UUID.random()
        coEvery { paymentRepository.get(id) } returns Payment(id = id, transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = providerId, storeId = storeId, amount = Money.of("20.00"), complete = true)
        val added = captureAdd()
        coEvery { paymentRepository.update(any()) } returns null // both the check-number update and the original-accumulation update vanish

        val refund = service.refundToCheck(id, Money.of("6.00"), checkNumber = "CK-9", reason = null, principalId = null)

        // The check-number update vanished, so `?: it` falls back to the recorded refund row, which never got the number applied.
        assertEquals(TransactionType.REFUND_TO_CHECK, refund.transactionType)
        assertEquals(null, refund.checkNumber)
        assertEquals(PaymentType.CHECK, added.captured.type) // the recorded refund row is the check-refund row
    }

    /** A gateway that always declines — drives the refund failure path. */
    private class DecliningPaymentProcessor : PaymentProcessor {
        override val key: String = "declining"
        override suspend fun submit(provider: PaymentProvider, amount: Money, currency: String, token: String?, creditCard: bosca.ecommerce.model.CreditCard?, save: Boolean, idempotencyKey: String?) =
            bosca.ecommerce.model.PaymentResult(complete = false, status = "FAILURE", error = "declined")
        override suspend fun refund(provider: PaymentProvider, originalTransactionId: String?, amount: Money, currency: String, idempotencyKey: String?) =
            bosca.ecommerce.model.PaymentResult(complete = false, status = "FAILURE", error = "declined")
        override suspend fun charge(provider: PaymentProvider, saved: SavedPaymentMethod, amount: Money, currency: String, idempotencyKey: String?) =
            bosca.ecommerce.model.PaymentResult(complete = false, status = "FAILURE", error = "declined")
    }

    /** Declines with only a `message` (no `error`) — exercises the `error ?: message` fallback in the refund check. */
    private class MessageOnlyDecliningProcessor : PaymentProcessor {
        override val key: String = "msg-decline"
        override suspend fun submit(provider: PaymentProvider, amount: Money, currency: String, token: String?, creditCard: bosca.ecommerce.model.CreditCard?, save: Boolean, idempotencyKey: String?) =
            bosca.ecommerce.model.PaymentResult(complete = false, status = "FAILURE", message = "soft decline")
        override suspend fun refund(provider: PaymentProvider, originalTransactionId: String?, amount: Money, currency: String, idempotencyKey: String?) =
            bosca.ecommerce.model.PaymentResult(complete = false, status = "FAILURE", message = "soft decline")
        override suspend fun charge(provider: PaymentProvider, saved: SavedPaymentMethod, amount: Money, currency: String, idempotencyKey: String?) =
            bosca.ecommerce.model.PaymentResult(complete = false, status = "FAILURE", message = "soft decline")
    }

    /** A PubSubService that records every publish — stands in for a subscribed consumer. */
    private class RecordingPubSub : bosca.pubsub.PubSubService {
        val published = mutableListOf<Pair<String, Any?>>()

        override suspend fun <T> publish(
            channel: String,
            serializer: kotlinx.serialization.SerializationStrategy<T>,
            message: T,
        ) {
            published += channel to message
        }

        override fun <T> subscribe(
            channel: String,
            deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        ): kotlinx.coroutines.flow.Flow<bosca.pubsub.Message<T>> = kotlinx.coroutines.flow.emptyFlow()
    }
}
