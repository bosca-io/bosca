package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.SubscriptionPlan
import bosca.serialization.UUID

/** Persistence for `ecom.subscription_plans` (unique store+key; `configuration` jsonb via JsonbMapper). */
@Repository
interface SubscriptionPlanRepository {

    @Query("select * from ecom.subscription_plans where id = :id and deleted is null")
    suspend fun get(id: UUID): SubscriptionPlan?

    @Query("select * from ecom.subscription_plans where store_id = :storeId and key = :key and deleted is null")
    suspend fun getByKey(storeId: UUID, key: String): SubscriptionPlan?

    @Query("select * from ecom.subscription_plans where plan_group_id = :planGroupId and deleted is null order by price")
    suspend fun getByGroup(planGroupId: UUID): List<SubscriptionPlan>

    @Query(
        """
        insert into ecom.subscription_plans
            (plan_group_id, store_id, key, name, description, status, price, interval, interval_unit, configuration, expires)
        values
            (:planGroupId, :storeId, :key, :name, :description, (:status)::ecom.subscription_plan_status, :price, :interval, (:intervalUnit)::ecom.subscription_interval_unit, :configuration, :expires)
        returning *
        """,
    )
    suspend fun add(plan: SubscriptionPlan): SubscriptionPlan
}
