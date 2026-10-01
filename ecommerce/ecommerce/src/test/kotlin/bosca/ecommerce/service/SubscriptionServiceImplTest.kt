@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.ecommerce.service

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Payment
import bosca.ecommerce.model.PaymentSubmitResult
import bosca.ecommerce.model.PaymentType
import bosca.ecommerce.model.StandardSubscriptionExtras
import bosca.ecommerce.model.SubscribeInput
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.model.TransactionType
import bosca.ecommerce.model.SavedChargeInput
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.ecommerce.repository.SubscriptionPlanGroupRepository
import bosca.ecommerce.repository.SubscriptionPlanRepository
import bosca.ecommerce.repository.SubscriptionRepository
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
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**subscription lifecycle — signup (no double), cancel, renewal success, and retry→UNPAID. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionServiceImplTest {

    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val planRepository = mockk<SubscriptionPlanRepository>()
    private val planGroupRepository = mockk<SubscriptionPlanGroupRepository>()
    private val paymentService = mockk<PaymentService>()
    private val storeService = mockk<StoreService>()
    private val catalogService = mockk<CatalogService>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: SubscriptionServiceImpl

    private val storeId = UUID.random()
    private val accountId = UUID.random()
    private val planId = UUID.random()
    private val planGroupId = UUID.random()
    private val subId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { io.mockk.mockk(relaxed = true) }
        coEvery { storeService.get(any()) } returns store()
        coEvery { catalogService.get(any()) } returns catalog()
        service = SubscriptionServiceImpl(subscriptionRepository, planRepository, planGroupRepository, paymentService, storeService, catalogService, auditService)
    }

    private fun store() = bosca.ecommerce.model.Store(
        id = storeId, identifier = "s", name = "S", companyId = UUID.random(), catalogId = UUID.random(),
        type = bosca.ecommerce.model.StoreType.VIRTUAL, paymentProviderId = UUID.random(), shippingCatalogProductId = UUID.random(),
    )

    private fun catalog(currency: String = "USD") = bosca.ecommerce.model.Catalog(
        id = UUID.random(), companyId = UUID.random(), key = "c", name = "C", currency = currency,
    )

    @AfterTest
    fun teardown() {
        unmockkAll()
        bosca.di.ProviderRegistry.clear()
    }

    private fun plan() = SubscriptionPlan(id = planId, planGroupId = planGroupId, storeId = storeId, key = "monthly", name = "Monthly", price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS)

    private fun captureAdd(): CapturingSlot<Subscription> {
        val slot = slot<Subscription>()
        coEvery { subscriptionRepository.add(capture(slot)) } answers { slot.captured.copy(id = subId) }
        return slot
    }

    private fun captureUpdate(): CapturingSlot<Subscription> {
        val slot = slot<Subscription>()
        coEvery { subscriptionRepository.update(capture(slot)) } answers { slot.captured }
        return slot
    }

    private fun dueSubscription(paymentFailures: Int = 0) = Subscription(
        id = subId, storeId = storeId, accountId = accountId, planId = planId, planGroupId = planGroupId,
        status = SubscriptionStatus.ACTIVE, price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        renews = OffsetDateTime.now().minusDays(1), paymentFailures = paymentFailures,
        extras = StandardSubscriptionExtras(savedPaymentMethod = SavedPaymentMethod("test", "save-1")),
    )

    private fun completedPayment(complete: Boolean, transportFailure: Boolean = false) = PaymentSubmitResult(
        Payment(id = UUID.random(), transactionType = TransactionType.PAYMENT, type = PaymentType.CREDIT_CARD, providerId = UUID.random(), storeId = storeId, amount = Money.of("10.00"), complete = complete),
        transportFailure = transportFailure,
    )

    private fun activeSubscription(nextId: UUID? = null) = Subscription(
        id = subId, storeId = storeId, accountId = accountId, planId = planId, planGroupId = planGroupId,
        status = SubscriptionStatus.ACTIVE, price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        renews = OffsetDateTime.now().plusDays(10), nextSubscriptionId = nextId,
        extras = StandardSubscriptionExtras(savedPaymentMethod = SavedPaymentMethod("test", "saved-1")),
    )

    @Test
    fun `changePlans schedules a pending successor and marks the current subscription expiring`() = runTest {
        val newPlanId = UUID.random()
        val successorId = UUID.random()
        val current = activeSubscription()
        coEvery { subscriptionRepository.getForUpdate(subId) } returns current
        coEvery { planRepository.get(newPlanId) } returns SubscriptionPlan(
            id = newPlanId, planGroupId = UUID.random(), storeId = storeId, key = "annual", name = "Annual", price = Money.of("100.00"), interval = 1, intervalUnit = IntervalUnit.YEARS,
        )
        val added = slot<Subscription>()
        coEvery { subscriptionRepository.add(capture(added)) } answers { added.captured.copy(id = successorId) }
        val next = slot<Subscription>()
        coEvery { subscriptionRepository.setNext(capture(next)) } answers { next.captured }

        service.changePlans(subId, newPlanId, current.extras, principalId = null)

        // The successor is PENDING on the new plan, renewing when the current subscription does, and
        // inherits the saved payment method so renewals keep working.
        assertEquals(SubscriptionStatus.PENDING, added.captured.status)
        assertEquals(newPlanId, added.captured.planId)
        assertEquals(current.renews, added.captured.renews)
        assertEquals(current.extras, added.captured.extras)
        // The current subscription is marked expiring into the successor.
        assertEquals(current.renews, next.captured.expires)
        assertEquals(successorId, next.captured.nextSubscriptionId)
    }

    @Test
    fun `changePlans back to the current plan clears the scheduled change`() = runTest {
        val scheduled = UUID.random()
        val current = activeSubscription(nextId = scheduled)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns current
        coEvery { subscriptionRepository.softDelete(scheduled) } returns Unit
        val next = slot<Subscription>()
        coEvery { subscriptionRepository.setNext(capture(next)) } answers { next.captured }

        service.changePlans(subId, planId, current.extras, principalId = null)

        coVerify(exactly = 1) { subscriptionRepository.softDelete(scheduled) } // drops the superseded successor
        coVerify(exactly = 0) { subscriptionRepository.add(any()) }
        assertEquals(null, next.captured.expires)
        assertEquals(null, next.captured.nextSubscriptionId)
    }

    @Test
    fun `setExtras replaces the subscription extras`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns activeSubscription()
        val slot = slot<Subscription>()
        coEvery { subscriptionRepository.updateExtras(capture(slot)) } answers { slot.captured }
        val newExtras = StandardSubscriptionExtras(savedPaymentMethod = SavedPaymentMethod("test", "new-method"))

        service.setExtras(subId, newExtras, principalId = null)

        assertEquals(newExtras, slot.captured.extras)
    }

    @Test
    fun `setExtras rejects a terminal subscription`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns activeSubscription().copy(status = SubscriptionStatus.EXPIRED)
        assertFailsWith<IllegalStateException> {
            service.setExtras(subId, StandardSubscriptionExtras(savedPaymentMethod = SavedPaymentMethod("test", "x")), principalId = null)
        }
    }

    @Test
    fun `subscribe creates an active subscription with a snapshot price and next renewal`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(accountId, planGroupId) } returns emptyList()
        val added = captureAdd()

        service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId, saved = SavedPaymentMethod("test", "v")), principalId = null)

        assertEquals(SubscriptionStatus.ACTIVE, added.captured.status)
        assertEquals(Money.of("10.00"), added.captured.price)
        assertEquals(planGroupId, added.captured.planGroupId)
        assertTrue(added.captured.renews.isAfter(OffsetDateTime.now()))
    }

    @Test
    fun `subscribe rejects a second live subscription in the same plan group`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(accountId, planGroupId) } returns listOf(dueSubscription())
        assertFailsWith<IllegalStateException> {
            service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId), principalId = null)
        }
    }

    @Test
    fun `cancel moves the subscription to cancelled`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns dueSubscription()
        val updated = captureUpdate()
        service.cancel(subId, principalId = null)
        assertEquals(SubscriptionStatus.CANCELLED, updated.captured.status)
    }

    @Test
    fun `cancel rejects an already-terminal subscription`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns dueSubscription().copy(status = SubscriptionStatus.CANCELLED)
        assertFailsWith<IllegalStateException> { service.cancel(subId, principalId = null) }
    }

    private fun planGroup() = SubscriptionPlanGroup(id = planGroupId, storeId = storeId, key = "g", name = "G", paymentRetries = 3)

    // The sweep drains in batches, so the due query returns the batch then empty (else the loop spins).
    private fun dueThenEmpty(sub: Subscription) {
        coEvery { subscriptionRepository.getDueForRenewal(any()) } returns listOf(sub) andThen emptyList()
    }

    @Test
    fun `renewDue charges the saved method and advances on success`() = runTest {
        val sub = dueSubscription()
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = true)
        val updated = captureUpdate()

        val count = service.renewDue()

        assertEquals(1, count)
        assertEquals(SubscriptionStatus.ACTIVE, updated.captured.status)
        assertEquals(1, updated.captured.renewals)
        assertEquals(0, updated.captured.paymentFailures)
        assertTrue(updated.captured.renews.isAfter(sub.renews))
        // A renewal is a system action — its audit entry carries a null principal.
        coVerify { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("renewed"), any(), any(), any(), isNull(), any(), any(), any()) }
    }

    @Test
    fun `renewDue charges with a deterministic idempotency key per subscription period`() = runTest {
        // The renewal key is stable per (subscription, billing period), so a redelivered renewal job replays
        // the SAME key and the gateway dedupes to one charge — not a second renewal for the same period.
        val sub = dueSubscription()
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        val charge = slot<SavedChargeInput>()
        coEvery { paymentService.chargeSaved(capture(charge), any()) } returns completedPayment(complete = true)
        captureUpdate()

        service.renewDue()

        assertEquals("renewal:${sub.id}:${sub.renews}", charge.captured.idempotencyKey)
        // The currency is snapshotted from the subscription (single-currency-of-record).
        assertEquals(sub.currency, charge.captured.currency)
    }

    @Test
    fun `renewDue marks past due on a failure with retries remaining`() = runTest {
        val sub = dueSubscription(paymentFailures = 0)
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = false)
        val updated = captureUpdate()

        service.renewDue()

        assertEquals(SubscriptionStatus.PAST_DUE, updated.captured.status)
        assertEquals(1, updated.captured.paymentFailures)
        assertTrue(updated.captured.renews.isAfter(sub.renews)) // retry pushed forward
    }

    @Test
    fun `renewDue defers without dunning on a gateway transport failure`() = runTest {
        // A subscription mid-dunning: a transient gateway OUTAGE (5xx/timeout) must not advance it.
        val sub = dueSubscription(paymentFailures = 1)
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = false, transportFailure = true)
        val updated = captureUpdate()

        service.renewDue()

        // Outage, not a decline: status stays ACTIVE and paymentFailures is NOT incremented...
        assertEquals(SubscriptionStatus.ACTIVE, updated.captured.status)
        assertEquals(1, updated.captured.paymentFailures)
        // ...but `renews` is nudged just past now so this run's due-loop still terminates (it retries next run).
        assertTrue(updated.captured.renews.isAfter(sub.renews))
        coVerify { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("renewal_deferred_transport"), any(), any(), any(), isNull(), any(), any(), any()) }
        coVerify(exactly = 0) { auditService.record<Subscription>(any(), any(), eq("renewal_failed"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renewDue marks unpaid without charging when there is no saved payment method`() = runTest {
        val sub = dueSubscription().copy(extras = StandardSubscriptionExtras(savedPaymentMethod = null))
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        val updated = captureUpdate()

        service.renewDue()

        assertEquals(SubscriptionStatus.UNPAID, updated.captured.status) // `?.savedPaymentMethod` -> null arm
        coVerify(exactly = 0) { paymentService.chargeSaved(any(), any()) } // never reached the gateway
    }

    @Test
    fun `renewDue marks unpaid once retries are exhausted`() = runTest {
        val sub = dueSubscription(paymentFailures = 2) // paymentRetries = 3
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = false)
        val updated = captureUpdate()

        service.renewDue()

        assertEquals(SubscriptionStatus.UNPAID, updated.captured.status)
        assertEquals(3, updated.captured.paymentFailures)
    }

    @Test
    fun `get and getByAccount delegate to the repository`() = runTest {
        coEvery { subscriptionRepository.get(subId) } returns activeSubscription()
        coEvery { subscriptionRepository.getByAccount(accountId, 0, 50) } returns listOf(activeSubscription())
        assertEquals(subId, service.get(subId)?.id)
        assertEquals(1, service.getByAccount(accountId, 0, 50).size)
    }

    @Test
    fun `getByGroup and getByPlan delegate to the repository`() = runTest {
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns listOf(activeSubscription())
        coEvery { subscriptionRepository.getByAccountAndPlan(accountId, planId) } returns listOf(activeSubscription(), activeSubscription())
        assertEquals(1, service.getByGroup(accountId, planGroupId).size)
        assertEquals(2, service.getByPlan(accountId, planId).size)
    }

    @Test
    fun `subscribe stamps the catalog currency onto the subscription`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(accountId, planGroupId) } returns emptyList()
        coEvery { catalogService.get(any()) } returns catalog("EUR")
        val added = captureAdd()

        service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId, saved = SavedPaymentMethod("test", "v")), principalId = null)

        assertEquals("EUR", added.captured.currency)
    }

    @Test
    fun `subscribe and createFromCart fail when the store is missing`() = runTest {
        coEvery { planRepository.get(any()) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(any(), any()) } returns emptyList()
        coEvery { storeService.get(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId, saved = SavedPaymentMethod("test", "v")), principalId = null)
        }
        assertFailsWith<IllegalStateException> {
            service.createFromCart(storeId, accountId, planId, UUID.random(), null, null, principalId = null)
        }
    }

    @Test
    fun `subscribe and createFromCart fail when the store's catalog is missing`() = runTest {
        coEvery { planRepository.get(any()) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(any(), any()) } returns emptyList()
        coEvery { catalogService.get(any()) } returns null
        assertFailsWith<IllegalStateException> {
            service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId, saved = SavedPaymentMethod("test", "v")), principalId = null)
        }
        assertFailsWith<IllegalStateException> {
            service.createFromCart(storeId, accountId, planId, UUID.random(), null, null, principalId = null)
        }
    }

    @Test
    fun `renew charges a single due subscription and returns it advanced`() = runTest {
        val sub = dueSubscription()
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = true)
        captureUpdate()

        val result = service.renew(subId)

        assertEquals(SubscriptionStatus.ACTIVE, result.status)
        assertEquals(1, result.renewals)
        assertTrue(result.renews.isAfter(sub.renews))
    }

    @Test
    fun `renew throws when the subscription does not exist`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns null
        assertFailsWith<IllegalStateException> { service.renew(subId) }
    }

    @Test
    fun `renew returns the subscription untouched when it is not eligible`() = runTest {
        // Cancelled (wrong status) and not-yet-due subscriptions are returned unchanged, with no charge.
        val cancelled = activeSubscription().copy(status = SubscriptionStatus.CANCELLED)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns cancelled
        assertEquals(SubscriptionStatus.CANCELLED, service.renew(subId).status)

        val notDue = activeSubscription() // renews 10 days out
        coEvery { subscriptionRepository.getForUpdate(subId) } returns notDue
        assertEquals(notDue.renews, service.renew(subId).renews)

        coVerify(exactly = 0) { paymentService.chargeSaved(any(), any()) }
    }

    @Test
    fun `subscribe throws when the plan is absent`() = runTest {
        coEvery { planRepository.get(planId) } returns null
        assertFailsWith<IllegalStateException> {
            service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId), principalId = null)
        }
    }

    @Test
    fun `createFromCart creates an active subscription bound to the cart`() = runTest {
        val cartId = UUID.random()
        val lastPaymentId = UUID.random()
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(accountId, planGroupId) } returns emptyList()
        val added = captureAdd()

        service.createFromCart(storeId, accountId, planId, cartId, SavedPaymentMethod("test", "v"), lastPaymentId, principalId = null)

        assertEquals(SubscriptionStatus.ACTIVE, added.captured.status)
        assertEquals(cartId, added.captured.cartId)
        assertEquals(lastPaymentId, added.captured.lastPaymentId)
    }

    @Test
    fun `createFromCart throws when the plan is absent`() = runTest {
        coEvery { planRepository.get(planId) } returns null
        assertFailsWith<IllegalStateException> {
            service.createFromCart(storeId, accountId, planId, UUID.random(), null, null, principalId = null)
        }
    }

    @Test
    fun `createFromCart rejects a second live subscription in the same group`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getLiveByAccountAndGroup(accountId, planGroupId) } returns listOf(dueSubscription())
        assertFailsWith<IllegalStateException> {
            service.createFromCart(storeId, accountId, planId, UUID.random(), null, null, principalId = null)
        }
    }

    @Test
    fun `cancel throws when the subscription is absent`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns null
        assertFailsWith<IllegalStateException> { service.cancel(subId, principalId = null) }
    }

    @Test
    fun `changePlans throws when the subscription is absent`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns null
        assertFailsWith<IllegalStateException> { service.changePlans(subId, UUID.random(), activeSubscription().extras, principalId = null) }
    }

    @Test
    fun `changePlans rejects a non-active subscription`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns activeSubscription().copy(status = SubscriptionStatus.PAST_DUE)
        assertFailsWith<IllegalStateException> { service.changePlans(subId, UUID.random(), activeSubscription().extras, principalId = null) }
    }

    @Test
    fun `changePlans throws when the new plan is absent`() = runTest {
        val newPlanId = UUID.random()
        coEvery { subscriptionRepository.getForUpdate(subId) } returns activeSubscription()
        coEvery { planRepository.get(newPlanId) } returns null
        assertFailsWith<IllegalStateException> { service.changePlans(subId, newPlanId, activeSubscription().extras, principalId = null) }
    }

    @Test
    fun `setExtras throws when the subscription is absent`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns null
        assertFailsWith<IllegalStateException> { service.setExtras(subId, activeSubscription().extras, principalId = null) }
    }

    @Test
    fun `renewDue marks unpayable when no saved method is on file`() = runTest {
        val sub = dueSubscription().copy(extras = StandardSubscriptionExtras(savedPaymentMethod = null))
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        val updated = captureUpdate()

        service.renewDue()

        assertEquals(SubscriptionStatus.UNPAID, updated.captured.status)
        coVerify(exactly = 0) { paymentService.chargeSaved(any(), any()) }
    }

    @Test
    fun `renewDue skips a subscription that is not yet due`() = runTest {
        val notYet = dueSubscription().copy(renews = OffsetDateTime.now().plusDays(5))
        dueThenEmpty(notYet)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns notYet

        val count = service.renewDue()

        assertEquals(1, count)
        coVerify(exactly = 0) { subscriptionRepository.update(any()) }
    }

    @Test
    fun `renewDue skips a subscription that is neither active nor past due`() = runTest {
        val cancelled = dueSubscription().copy(status = SubscriptionStatus.CANCELLED)
        dueThenEmpty(cancelled)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns cancelled

        service.renewDue()

        coVerify(exactly = 0) { subscriptionRepository.update(any()) }
    }

    @Test
    fun `renewDue skips when the locked row has vanished`() = runTest {
        dueThenEmpty(dueSubscription())
        coEvery { subscriptionRepository.getForUpdate(subId) } returns null

        service.renewDue()

        coVerify(exactly = 0) { subscriptionRepository.update(any()) }
    }

    @Test
    fun `renewDue returns zero when nothing is due`() = runTest {
        coEvery { subscriptionRepository.getDueForRenewal(any()) } returns emptyList()
        assertEquals(0, service.renewDue())
        coVerify(exactly = 0) { subscriptionRepository.getForUpdate(any()) }
    }

    @Test
    fun `cancel throws when the update returns null`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns dueSubscription()
        coEvery { subscriptionRepository.update(any()) } returns null
        assertFailsWith<IllegalStateException> { service.cancel(subId, principalId = null) }
    }

    @Test
    fun `setExtras throws when the update returns null`() = runTest {
        coEvery { subscriptionRepository.getForUpdate(subId) } returns activeSubscription()
        coEvery { subscriptionRepository.updateExtras(any()) } returns null
        assertFailsWith<IllegalStateException> { service.setExtras(subId, activeSubscription().extras, principalId = null) }
    }

    @Test
    fun `changePlans revert clears the schedule without a prior successor`() = runTest {
        val current = activeSubscription() // no nextSubscriptionId -> no softDelete
        coEvery { subscriptionRepository.getForUpdate(subId) } returns current
        val next = slot<Subscription>()
        coEvery { subscriptionRepository.setNext(capture(next)) } answers { next.captured }

        service.changePlans(subId, planId, current.extras, principalId = null)

        coVerify(exactly = 0) { subscriptionRepository.softDelete(any()) }
        coVerify(exactly = 0) { subscriptionRepository.add(any()) }
        assertEquals(null, next.captured.expires)
        assertEquals(null, next.captured.nextSubscriptionId)
    }

    @Test
    fun `changePlans revert falls back to the original when setNext returns null`() = runTest {
        val current = activeSubscription()
        coEvery { subscriptionRepository.getForUpdate(subId) } returns current
        coEvery { subscriptionRepository.setNext(any()) } returns null // persist no-op -> ?: sub

        val result = service.changePlans(subId, planId, current.extras, principalId = null)

        assertEquals(current.id, result.id)
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("plan_change_reverted"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `changePlans tolerates a null setNext when scheduling a new successor`() = runTest {
        val newPlanId = UUID.random()
        val successorId = UUID.random()
        val current = activeSubscription()
        coEvery { subscriptionRepository.getForUpdate(subId) } returns current
        coEvery { planRepository.get(newPlanId) } returns SubscriptionPlan(
            id = newPlanId, planGroupId = UUID.random(), storeId = storeId, key = "annual", name = "Annual", price = Money.of("100.00"), interval = 1, intervalUnit = IntervalUnit.YEARS,
        )
        val added = slot<Subscription>()
        coEvery { subscriptionRepository.add(capture(added)) } answers { added.captured.copy(id = successorId) }
        coEvery { subscriptionRepository.setNext(any()) } returns null // expiring write no-op -> ?: sub

        val result = service.changePlans(subId, newPlanId, current.extras, principalId = null)

        // The returned value is the new PENDING successor regardless of the setNext fallback.
        assertEquals(successorId, result.id)
        assertEquals(SubscriptionStatus.PENDING, result.status)
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(successorId), eq("plan_change_scheduled"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renewDue renews a past due subscription on a successful retry`() = runTest {
        val sub = dueSubscription(paymentFailures = 1).copy(status = SubscriptionStatus.PAST_DUE)
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = true)
        val updated = captureUpdate()

        service.renewDue()

        // Recovery resets the subscription to ACTIVE and clears the failure counter.
        assertEquals(SubscriptionStatus.ACTIVE, updated.captured.status)
        assertEquals(0, updated.captured.paymentFailures)
        assertEquals(1, updated.captured.renewals)
    }

    @Test
    fun `renewDue tolerates a null update on a successful renewal`() = runTest {
        val sub = dueSubscription()
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = true)
        coEvery { subscriptionRepository.update(any()) } returns null // persist no-op -> ?: sub

        val count = service.renewDue()

        assertEquals(1, count)
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("renewed"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renewDue tolerates a null update on a failed renewal`() = runTest {
        val sub = dueSubscription(paymentFailures = 0)
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns planGroup()
        coEvery { paymentService.chargeSaved(any(), any()) } returns completedPayment(complete = false)
        coEvery { subscriptionRepository.update(any()) } returns null // persist no-op -> ?: sub

        service.renewDue()

        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("renewal_failed"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renewDue tolerates a null update when no saved method is on file`() = runTest {
        val sub = dueSubscription().copy(extras = StandardSubscriptionExtras(savedPaymentMethod = null))
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { subscriptionRepository.update(any()) } returns null // persist no-op -> ?: sub

        service.renewDue()

        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("renewal_unpayable"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `renewDue throws when the plan group has vanished`() = runTest {
        val sub = dueSubscription()
        dueThenEmpty(sub)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns sub
        coEvery { planGroupRepository.get(planGroupId) } returns null
        // renewOne runs inside its own transaction; the error surfaces through the pass-through mock.
        assertFailsWith<IllegalStateException> { service.renewDue() }
    }

    // --- grantExternal ---

    @Test
    fun `grantExternal creates an active external subscription with no saved method`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns emptyList()
        val added = captureAdd()

        val before = OffsetDateTime.now()
        service.grantExternal(storeId, accountId, planId, principalId = null)

        assertEquals(SubscriptionStatus.ACTIVE, added.captured.status)
        assertTrue(added.captured.external, "an IAP grant is externally billed")
        assertEquals(Money.of("10.00"), added.captured.price)
        assertEquals(planGroupId, added.captured.planGroupId)
        // No reusable method on file — the store bills it, not the renewal sweep.
        assertEquals(null, (added.captured.extras as StandardSubscriptionExtras).savedPaymentMethod)
        assertTrue(added.captured.renews.isAfter(before))
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("iap_granted"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grantExternal extends an existing live external subscription by one interval`() = runTest {
        val existing = Subscription(
            id = subId, storeId = storeId, accountId = accountId, planId = planId, planGroupId = planGroupId,
            status = SubscriptionStatus.ACTIVE, price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
            renews = OffsetDateTime.now().plusDays(3), renewals = 4, external = true,
            extras = StandardSubscriptionExtras(savedPaymentMethod = null),
        )
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns listOf(existing)
        val updated = captureUpdate()

        service.grantExternal(storeId, accountId, planId, principalId = null)

        assertEquals(SubscriptionStatus.ACTIVE, updated.captured.status)
        assertEquals(5, updated.captured.renewals) // renewals + 1
        assertTrue(updated.captured.renews.isAfter(existing.renews)) // advanced by one interval
        coVerify(exactly = 0) { subscriptionRepository.add(any()) } // extended, not duplicated
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("iap_extended"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grantExternal ignores a non-external or terminal subscription in the group and creates anew`() = runTest {
        // A non-external sub and a terminal external sub both fail the live-external filter -> CREATE.
        val nonExternal = activeSubscription() // external = false
        val cancelledExternal = activeSubscription().copy(status = SubscriptionStatus.CANCELLED, external = true)
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns listOf(nonExternal, cancelledExternal)
        val added = captureAdd()

        service.grantExternal(storeId, accountId, planId, principalId = null)

        assertTrue(added.captured.external)
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("iap_granted"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grantExternal tolerates a null update when extending`() = runTest {
        val existing = Subscription(
            id = subId, storeId = storeId, accountId = accountId, planId = planId, planGroupId = planGroupId,
            status = SubscriptionStatus.PENDING, price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
            renews = OffsetDateTime.now().plusDays(3), external = true,
            extras = StandardSubscriptionExtras(savedPaymentMethod = null),
        )
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns listOf(existing)
        coEvery { subscriptionRepository.update(any()) } returns null // persist no-op -> ?: existing

        val result = service.grantExternal(storeId, accountId, planId, principalId = null)

        assertEquals(existing.id, result.id)
        coVerify(exactly = 1) { auditService.record<Subscription>(eq("subscription"), eq(subId), eq("iap_extended"), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `grantExternal throws when the plan is absent`() = runTest {
        coEvery { planRepository.get(planId) } returns null
        assertFailsWith<IllegalStateException> { service.grantExternal(storeId, accountId, planId, principalId = null) }
    }

    @Test
    fun `grantExternal throws when the store is missing`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns emptyList()
        coEvery { storeService.get(any()) } returns null
        assertFailsWith<IllegalStateException> { service.grantExternal(storeId, accountId, planId, principalId = null) }
    }

    @Test
    fun `grantExternal throws when the catalog is missing`() = runTest {
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { subscriptionRepository.getByAccountAndGroup(accountId, planGroupId) } returns emptyList()
        coEvery { catalogService.get(any()) } returns null
        assertFailsWith<IllegalStateException> { service.grantExternal(storeId, accountId, planId, principalId = null) }
    }

    @Test
    fun `renew never charges or duns an external subscription`() = runTest {
        // An externally-billed subscription is billed by the store; the sweep must leave it untouched.
        val external = dueSubscription().copy(external = true)
        coEvery { subscriptionRepository.getForUpdate(subId) } returns external

        val result = service.renew(subId)

        assertEquals(external, result) // returned unchanged
        coVerify(exactly = 0) { paymentService.chargeSaved(any(), any()) }
        coVerify(exactly = 0) { subscriptionRepository.update(any()) }
    }

    @Test
    fun `subscribe advances renewal by each interval unit`() = runTest {
        // Exercises every plusInterval branch through the public subscribe path.
        for (unit in IntervalUnit.entries) {
            coEvery { planRepository.get(planId) } returns plan().copy(interval = 2, intervalUnit = unit)
            coEvery { subscriptionRepository.getLiveByAccountAndGroup(accountId, planGroupId) } returns emptyList()
            val added = captureAdd()

            val before = OffsetDateTime.now()
            service.subscribe(SubscribeInput(storeId = storeId, accountId = accountId, planId = planId, saved = SavedPaymentMethod("test", "v")), principalId = null)

            assertEquals(unit, added.captured.intervalUnit)
            assertTrue(added.captured.renews.isAfter(before), "renews must advance for $unit")
        }
    }
}
