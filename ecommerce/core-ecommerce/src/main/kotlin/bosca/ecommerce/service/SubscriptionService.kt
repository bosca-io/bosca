package bosca.ecommerce.service

import bosca.ecommerce.model.SubscribeInput
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionExtras
import bosca.ecommerce.model.SavedPaymentMethod
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Subscriptions. An account may hold only one live subscription per plan group (no double-subscribe).
 * [renewDue] is the runner sweep: it charges each due subscription's saved method and advances or,
 * after the plan group's retry cap, marks it UNPAID.
 */
interface SubscriptionService : Service {

    suspend fun get(id: UUID): Subscription?

    /** A page of an account's subscriptions, newest first. */
    suspend fun getByAccount(accountId: UUID, offset: Int, limit: Int): List<Subscription>

    /** An account's subscriptions within a plan group (legacy `getSubscriptionsByGroup`). */
    suspend fun getByGroup(accountId: UUID, planGroupId: UUID): List<Subscription>

    /** An account's subscriptions on a specific plan (legacy `getSubscriptionsByPlan`). */
    suspend fun getByPlan(accountId: UUID, planId: UUID): List<Subscription>

    /** Subscribe an account to a plan directly (admin/API path). Fails if already subscribed to the group. */
    suspend fun subscribe(input: SubscribeInput, principalId: UUID?): Subscription

    /**
     * Create a subscription from a checkout (cart) line: snapshots the plan price, records the
     * originating [cartId], and stores the [saved] method for renewals. Fails on double-subscribe.
     */
    suspend fun createFromCart(
        storeId: UUID,
        accountId: UUID,
        planId: UUID,
        cartId: UUID,
        saved: SavedPaymentMethod?,
        lastPaymentId: UUID?,
        principalId: UUID?,
    ): Subscription

    /** Cancel a subscription. */
    suspend fun cancel(id: UUID, principalId: UUID?): Subscription

    /**
     * Schedule a plan change that takes effect at the next renewal (legacy `changePlans`): creates a
     * PENDING successor on [newPlanId] (carrying [extras]) renewing when the current one does, and
     * marks the current subscription as expiring into it. Changing back to the current plan clears any
     * scheduled change. Returns the successor (or the reverted current subscription). Requires ACTIVE.
     */
    suspend fun changePlans(id: UUID, newPlanId: UUID, extras: SubscriptionExtras, principalId: UUID?): Subscription

    /** Replace a subscription's [Subscription.extras] (legacy `setExtras`). Audited. */
    suspend fun setExtras(id: UUID, extras: SubscriptionExtras, principalId: UUID?): Subscription

    /** Grant or extend an externally-billed entitlement (e.g. app-store IAP): creates an ACTIVE external
     *  subscription on [planId] (no saved method), or advances an existing live one in the plan's group by
     *  one plan interval. The renewal sweep never charges or duns external subscriptions. */
    suspend fun grantExternal(storeId: UUID, accountId: UUID, planId: UUID, principalId: UUID?): Subscription

    /**
     * Renew ALL due subscriptions (the runner sweep), batching internally until the due set is
     * drained — never capped, so no subscription is silently skipped. Returns how many were processed.
     */
    suspend fun renewDue(): Int

    /**
     * Renew a single subscription now (legacy `renew`) — the same per-subscription renewal the runner
     * sweep performs (charge the saved method and advance, or count the failure / mark UNPAID). For an
     * operator "renew now" / retry. Returns the resulting subscription; throws if it does not exist.
     */
    suspend fun renew(id: UUID): Subscription
}
