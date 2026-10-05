package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.events.SubscriptionCreated
import bosca.ecommerce.events.SubscriptionRenewalFailed
import bosca.ecommerce.events.SubscriptionRenewed
import bosca.ecommerce.events.SubscriptionStatusChanged
import bosca.ecommerce.events.dispatch
import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.StandardSubscriptionExtras
import bosca.ecommerce.model.SubscribeInput
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionExtras
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.model.SavedChargeInput
import bosca.ecommerce.repository.SubscriptionPlanGroupRepository
import bosca.ecommerce.repository.SubscriptionPlanRepository
import bosca.ecommerce.repository.SubscriptionRepository
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory

/**
 * Subscriptions. Signup enforces one live subscription per plan group. [renewDue] charges each due
 * subscription's saved method and advances [Subscription.renews]; on failure it counts up
 * and, once the plan group's retry cap is hit, marks the subscription UNPAID.
 */
@ServiceImplementation
class SubscriptionServiceImpl(
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val planGroupRepository: SubscriptionPlanGroupRepository,
    private val paymentService: PaymentService,
    private val storeService: StoreService,
    private val catalogService: CatalogService,
    private val auditService: EcomAuditService,
) : SubscriptionService {

    override suspend fun get(id: UUID): Subscription? = subscriptionRepository.get(id)

    override suspend fun getByAccount(accountId: UUID, offset: Int, limit: Int): List<Subscription> =
        subscriptionRepository.getByAccount(accountId, offset, limit)

    override suspend fun getByGroup(accountId: UUID, planGroupId: UUID): List<Subscription> =
        subscriptionRepository.getByAccountAndGroup(accountId, planGroupId)

    override suspend fun getByPlan(accountId: UUID, planId: UUID): List<Subscription> =
        subscriptionRepository.getByAccountAndPlan(accountId, planId)

    override suspend fun subscribe(input: SubscribeInput, principalId: UUID?): Subscription = transaction {
        val plan = planRepository.get(input.planId) ?: error("plan ${input.planId} not found")
        check(subscriptionRepository.getLiveByAccountAndGroup(input.accountId, plan.planGroupId).isEmpty()) {
            "account ${input.accountId} already has a live subscription in plan group ${plan.planGroupId}"
        }
        val store = storeService.get(input.storeId) ?: error("store ${input.storeId} not found")
        val catalog = catalogService.get(store.catalogId) ?: error("catalog ${store.catalogId} not found")
        val currency = catalog.currency
        val now = OffsetDateTime.now()
        val subscription = subscriptionRepository.add(
            Subscription(
                storeId = input.storeId, accountId = input.accountId, planId = plan.id, planGroupId = plan.planGroupId,
                status = SubscriptionStatus.ACTIVE, price = plan.price, currency = currency, interval = plan.interval, intervalUnit = plan.intervalUnit,
                renews = now.plusInterval(plan.interval, plan.intervalUnit),
                extras = StandardSubscriptionExtras(savedPaymentMethod = input.saved),
            ),
        )
        audit(subscription, "subscribed", principalId)
        subscription.createdEvent().dispatch()
        subscription
    }

    override suspend fun createFromCart(
        storeId: UUID,
        accountId: UUID,
        planId: UUID,
        cartId: UUID,
        saved: bosca.ecommerce.model.SavedPaymentMethod?,
        lastPaymentId: UUID?,
        principalId: UUID?,
    ): Subscription = transaction {
        val plan = planRepository.get(planId) ?: error("plan $planId not found")
        check(subscriptionRepository.getLiveByAccountAndGroup(accountId, plan.planGroupId).isEmpty()) {
            "account $accountId already has a live subscription in plan group ${plan.planGroupId}"
        }
        val store = storeService.get(storeId) ?: error("store $storeId not found")
        val catalog = catalogService.get(store.catalogId) ?: error("catalog ${store.catalogId} not found")
        val currency = catalog.currency
        val now = OffsetDateTime.now()
        val subscription = subscriptionRepository.add(
            Subscription(
                storeId = storeId, accountId = accountId, planId = plan.id, planGroupId = plan.planGroupId, cartId = cartId,
                status = SubscriptionStatus.ACTIVE, price = plan.price, currency = currency, interval = plan.interval, intervalUnit = plan.intervalUnit,
                renews = now.plusInterval(plan.interval, plan.intervalUnit), lastPaymentId = lastPaymentId,
                extras = StandardSubscriptionExtras(savedPaymentMethod = saved),
            ),
        )
        audit(subscription, "subscribed_from_cart", principalId)
        subscription.createdEvent().dispatch()
        subscription
    }

    /** Terminal subscription states — no further lifecycle transition or edit is allowed. */
    private val terminalStatuses = setOf(SubscriptionStatus.CANCELLED, SubscriptionStatus.EXPIRED, SubscriptionStatus.DELETED)

    override suspend fun cancel(id: UUID, principalId: UUID?): Subscription = transaction {
        val existing = subscriptionRepository.getForUpdate(id) ?: error("subscription $id not found")
        check(existing.status !in terminalStatuses) { "subscription $id is already ${existing.status}" }
        val cancelled = subscriptionRepository.update(existing.copy(status = SubscriptionStatus.CANCELLED))
            ?: error("subscription $id not found")
        audit(cancelled, "cancelled", principalId)
        dispatchStatusChange(existing, cancelled)
        cancelled
    }

    override suspend fun changePlans(id: UUID, newPlanId: UUID, extras: SubscriptionExtras, principalId: UUID?): Subscription = transaction {
        val sub = subscriptionRepository.getForUpdate(id) ?: error("subscription $id not found")
        check(sub.status == SubscriptionStatus.ACTIVE) { "subscription $id isn't active" }
        // Drop any previously-scheduled successor before scheduling a new outcome.
        sub.nextSubscriptionId?.let { subscriptionRepository.softDelete(it) }
        // Reverting to the current plan just clears the scheduled change.
        if (sub.planId == newPlanId) {
            val reverted = subscriptionRepository.setNext(sub.copy(expires = null, nextSubscriptionId = null)) ?: sub
            audit(reverted, "plan_change_reverted", principalId)
            return@transaction reverted
        }
        val newPlan = planRepository.get(newPlanId) ?: error("plan $newPlanId not found")
        // The PENDING successor takes over when the current subscription next renews.
        val next = subscriptionRepository.add(
            Subscription(
                storeId = sub.storeId, accountId = sub.accountId, planId = newPlan.id, planGroupId = newPlan.planGroupId,
                status = SubscriptionStatus.PENDING, price = newPlan.price, currency = sub.currency, interval = newPlan.interval, intervalUnit = newPlan.intervalUnit,
                renews = sub.renews, extras = extras,
            ),
        )
        val expiring = subscriptionRepository.setNext(sub.copy(expires = sub.renews, nextSubscriptionId = next.id)) ?: sub
        audit(next, "plan_change_scheduled", principalId)
        audit(expiring, "plan_change_expiring", principalId)
        next
    }

    override suspend fun setExtras(id: UUID, extras: SubscriptionExtras, principalId: UUID?): Subscription = transaction {
        val sub = subscriptionRepository.getForUpdate(id) ?: error("subscription $id not found")
        check(sub.status !in terminalStatuses) { "subscription $id is ${sub.status} — cannot edit a terminal subscription" }
        val updated = subscriptionRepository.updateExtras(sub.copy(extras = extras)) ?: error("subscription $id not found")
        audit(updated, "extras_updated", principalId)
        updated
    }

    override suspend fun grantExternal(storeId: UUID, accountId: UUID, planId: UUID, principalId: UUID?): Subscription = transaction {
        val plan = planRepository.get(planId) ?: error("plan $planId not found")
        val store = storeService.get(storeId) ?: error("store $storeId not found")
        val catalog = catalogService.get(store.catalogId) ?: error("catalog ${store.catalogId} not found")
        val currency = catalog.currency
        val now = OffsetDateTime.now()
        // An existing live, externally-billed entitlement in this plan group is extended rather than duplicated.
        val existing = subscriptionRepository.getByAccountAndGroup(accountId, plan.planGroupId).firstOrNull {
            it.external && it.deleted == null && (it.status == SubscriptionStatus.ACTIVE || it.status == SubscriptionStatus.PENDING)
        }
        if (existing != null) {
            val extended = subscriptionRepository.update(
                existing.copy(
                    status = SubscriptionStatus.ACTIVE,
                    renews = existing.renews.plusInterval(plan.interval, plan.intervalUnit),
                    renewals = existing.renewals + 1,
                ),
            ) ?: existing
            audit(extended, "iap_extended", principalId)
            return@transaction extended
        }
        val subscription = subscriptionRepository.add(
            Subscription(
                storeId = storeId, accountId = accountId, planId = plan.id, planGroupId = plan.planGroupId,
                status = SubscriptionStatus.ACTIVE, price = plan.price, currency = currency, interval = plan.interval, intervalUnit = plan.intervalUnit,
                renews = now.plusInterval(plan.interval, plan.intervalUnit),
                external = true,
                extras = StandardSubscriptionExtras(savedPaymentMethod = null),
            ),
        )
        audit(subscription, "iap_granted", principalId)
        subscription.createdEvent().dispatch()
        subscription
    }

    override suspend fun renewDue(): Int {
        var processed = 0
        // Drain the whole due set in batches; each renewal advances `renews` (success -> next billing,
        // failure -> a retry delay), so processed subscriptions leave the due set and the loop ends.
        while (true) {
            val batch = subscriptionRepository.getDueForRenewal(BATCH_SIZE)
            if (batch.isEmpty()) break
            batch.forEach { renewOne(it.id) }
            processed += batch.size
        }
        return processed
    }

    override suspend fun renew(id: UUID): Subscription =
        renewOne(id) ?: error("subscription $id not found")

    /**
     * Renew one subscription under a row lock: charge the saved method, advance or fail-count. Returns
     * the resulting subscription (renewed / past-due / unpaid), the unchanged subscription when it isn't
     * eligible (wrong status or not yet due), or null when it does not exist.
     */
    private suspend fun renewOne(subscriptionId: UUID): Subscription? = transaction {
        val sub = subscriptionRepository.getForUpdate(subscriptionId) ?: return@transaction null
        // Externally-billed subscriptions (e.g. app-store IAP) are billed by the store — the sweep must
        // never charge, advance, or dun them. Return unchanged before any renewal work.
        if (sub.external) return@transaction sub
        if (sub.status != SubscriptionStatus.ACTIVE && sub.status != SubscriptionStatus.PAST_DUE) return@transaction sub
        if (sub.renews.isAfter(OffsetDateTime.now())) return@transaction sub
        val saved = (sub.extras as? StandardSubscriptionExtras)?.savedPaymentMethod
        if (saved == null) {
            // No reusable method on file — cannot renew.
            val unpaid = subscriptionRepository.update(sub.copy(status = SubscriptionStatus.UNPAID)) ?: sub
            audit(unpaid, "renewal_unpayable", null)
            dispatchStatusChange(sub, unpaid)
            SubscriptionRenewalFailed(subscriptionId = sub.id, accountId = sub.accountId, paymentFailures = sub.paymentFailures).dispatch()
            return@transaction unpaid
        }
        val group = planGroupRepository.get(sub.planGroupId) ?: error("plan group ${sub.planGroupId} not found")
        val charged = paymentService.chargeSaved(
            SavedChargeInput(
                storeId = sub.storeId, saved = saved, amount = sub.price, accountId = sub.accountId, subscriptionId = sub.id,
                // Stable per (subscription, period): a redelivered renewal job for the same period dedupes at the gateway.
                idempotencyKey = "renewal:${sub.id}:${sub.renews}",
                // Snapshot the subscription's currency so the renewal stays in the signup currency.
                currency = sub.currency,
            ),
            principalId = null,
        )
        val payment = charged.payment
        if (payment.complete) {
            val renewed = subscriptionRepository.update(
                sub.copy(
                    status = SubscriptionStatus.ACTIVE,
                    renews = sub.renews.plusInterval(sub.interval, sub.intervalUnit),
                    renewals = sub.renewals + 1, paymentFailures = 0, lastPaymentId = payment.id,
                ),
            ) ?: sub
            audit(renewed, "renewed", null)
            dispatchStatusChange(sub, renewed)
            SubscriptionRenewed(subscriptionId = renewed.id, accountId = renewed.accountId, paymentId = payment.id, renewals = renewed.renewals).dispatch()
            renewed
        } else if (charged.transportFailure) {
            // A gateway TRANSPORT failure (5xx / timeout / unparseable) is not a card decline — an outage must
            // not march subscriptions toward UNPAID. Do NOT increment paymentFailures or change status; just
            // nudge `renews` past now so this run's due-loop terminates, leaving it due for the next scheduled
            // run. Logged with the gateway status for triage.
            val deferred = subscriptionRepository.update(
                sub.copy(renews = OffsetDateTime.now().plusHours(TRANSPORT_RETRY_DELAY_HOURS)),
            ) ?: sub
            audit(deferred, "renewal_deferred_transport", null)
            log.error(
                "subscription ${sub.id} renewal deferred: payment gateway transport failure " +
                    "(providerStatus=${payment.providerStatus}, providerError=${payment.providerError}); not counted toward dunning",
            )
            deferred
        } else {
            val failures = sub.paymentFailures + 1
            val next = if (failures >= group.paymentRetries) {
                // Retries exhausted: UNPAID is no longer in the due query, so it won't be retried.
                sub.copy(status = SubscriptionStatus.UNPAID, paymentFailures = failures)
            } else {
                // Dunning: retry after a delay so it leaves the immediate due set (loop terminates).
                sub.copy(status = SubscriptionStatus.PAST_DUE, paymentFailures = failures, renews = OffsetDateTime.now().plusDays(RETRY_DELAY_DAYS))
            }
            val failed = subscriptionRepository.update(next) ?: sub
            audit(failed, "renewal_failed", null)
            dispatchStatusChange(sub, failed)
            SubscriptionRenewalFailed(subscriptionId = failed.id, accountId = failed.accountId, paymentFailures = failed.paymentFailures).dispatch()
            failed
        }
    }

    private fun OffsetDateTime.plusInterval(n: Int, unit: IntervalUnit): OffsetDateTime = when (unit) {
        IntervalUnit.SECONDS -> plusSeconds(n.toLong())
        IntervalUnit.MINUTES -> plusMinutes(n.toLong())
        IntervalUnit.HOURS -> plusHours(n.toLong())
        IntervalUnit.DAYS -> plusDays(n.toLong())
        IntervalUnit.MONTHS -> plusMonths(n.toLong())
        IntervalUnit.YEARS -> plusYears(n.toLong())
    }

    private fun Subscription.createdEvent(): SubscriptionCreated = SubscriptionCreated(
        subscriptionId = id, accountId = accountId, storeId = storeId, planId = planId, planGroupId = planGroupId, cartId = cartId,
    )

    /** Emits a status-change event only when the persisted status actually moved. */
    private suspend fun dispatchStatusChange(before: Subscription, after: Subscription) {
        if (before.status != after.status) {
            SubscriptionStatusChanged(
                subscriptionId = after.id, accountId = after.accountId, status = after.status, previousStatus = before.status,
            ).dispatch()
        }
    }

    private suspend fun audit(subscription: Subscription, action: String, principalId: UUID?) {
        auditService.record(
            entityType = "subscription", entityId = subscription.id, action = action,
            serializer = Subscription.serializer(),
            after = subscription,
            principalId = principalId, storeId = subscription.storeId,
        )
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val RETRY_DELAY_DAYS = 1L
        // Transport failures retry sooner than a decline's dunning delay — the gateway, not the card, is the
        // problem, so re-attempt promptly on the next scheduled run rather than waiting out the dunning window.
        const val TRANSPORT_RETRY_DELAY_HOURS = 1L
        private val log = LoggerFactory.getLogger(SubscriptionServiceImpl::class.java)
    }
}
