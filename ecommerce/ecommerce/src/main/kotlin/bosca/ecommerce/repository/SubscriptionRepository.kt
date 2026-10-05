package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.Subscription
import bosca.serialization.UUID

/** Persistence for `ecom.subscriptions` (`extras` jsonb via JsonbMapper; status is a native enum). */
@Repository
interface SubscriptionRepository {

    @Query("select * from ecom.subscriptions where id = :id and deleted is null")
    suspend fun get(id: UUID): Subscription?

    @Query("select * from ecom.subscriptions where id = :id and deleted is null for update")
    suspend fun getForUpdate(id: UUID): Subscription?

    /** A page of an account's subscriptions, newest first — bounds this append-mostly list ([Ecom.subscriptions]). */
    @Query("select * from ecom.subscriptions where account_id = :accountId and deleted is null order by created desc offset :offset limit :limit")
    suspend fun getByAccount(accountId: UUID, offset: Int, limit: Int): List<Subscription>

    /** An account's subscriptions within a plan group (legacy `getSubscriptionsByGroup`). */
    @Query("select * from ecom.subscriptions where account_id = :accountId and plan_group_id = :planGroupId and deleted is null order by created")
    suspend fun getByAccountAndGroup(accountId: UUID, planGroupId: UUID): List<Subscription>

    /** An account's subscriptions on a specific plan (legacy `getSubscriptionsByPlan`). */
    @Query("select * from ecom.subscriptions where account_id = :accountId and plan_id = :planId and deleted is null order by created")
    suspend fun getByAccountAndPlan(accountId: UUID, planId: UUID): List<Subscription>

    /** Live (not cancelled/expired/deleted) subscriptions for the no-double-subscribe check. */
    @Query(
        "select * from ecom.subscriptions where account_id = :accountId and plan_group_id = :planGroupId and deleted is null and status not in ('cancelled', 'expired', 'deleted')",
    )
    suspend fun getLiveByAccountAndGroup(accountId: UUID, planGroupId: UUID): List<Subscription>

    /** Subscriptions due for a renewal attempt (active or retrying), oldest first. Externally-billed
     *  subscriptions (e.g. app-store IAP) are never selected — the store bills them, not the sweep. */
    @Query(
        "select * from ecom.subscriptions where deleted is null and status in ('active', 'past_due') and external = false and renews <= now() order by renews limit :limit",
    )
    suspend fun getDueForRenewal(limit: Int): List<Subscription>

    @Query(
        """
        insert into ecom.subscriptions
            (store_id, account_id, plan_id, plan_group_id, cart_id, status, price, currency, interval, interval_unit,
             renews, expires, payment_failures, renewals, external, last_payment_id, next_subscription_id, extras)
        values
            (:storeId, :accountId, :planId, :planGroupId, :cartId, (:status)::ecom.subscription_status, :price, :currency, :interval, (:intervalUnit)::ecom.subscription_interval_unit,
             :renews, :expires, :paymentFailures, :renewals, :external, :lastPaymentId, :nextSubscriptionId, :extras)
        returning *
        """,
    )
    suspend fun add(subscription: Subscription): Subscription

    @Query(
        """
        update ecom.subscriptions
           set status = (:status)::ecom.subscription_status, renews = :renews, payment_failures = :paymentFailures,
               renewals = :renewals, external = :external, last_payment_id = :lastPaymentId, expires = :expires, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun update(subscription: Subscription): Subscription?

    /** Persist a scheduled plan change: the expiry into, and id of, the successor subscription. */
    @Query(
        """
        update ecom.subscriptions
           set expires = :expires, next_subscription_id = :nextSubscriptionId, modified = now()
         where id = :id and deleted is null
        returning *
        """,
    )
    suspend fun setNext(subscription: Subscription): Subscription?

    /** Persist the subscription's [Subscription.extras] (jsonb). */
    @Query("update ecom.subscriptions set extras = :extras, modified = now() where id = :id and deleted is null returning *")
    suspend fun updateExtras(subscription: Subscription): Subscription?

    /** Soft-delete a subscription (used to drop a superseded pending plan change). */
    @Query("update ecom.subscriptions set deleted = now(), modified = now() where id = :id and deleted is null")
    suspend fun softDelete(id: UUID)
}
