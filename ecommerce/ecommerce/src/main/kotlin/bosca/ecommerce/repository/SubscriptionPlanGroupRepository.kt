package bosca.ecommerce.repository

import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.serialization.UUID

/** Persistence for `ecom.subscription_plan_groups`. */
@Repository
interface SubscriptionPlanGroupRepository {

    @Query("select * from ecom.subscription_plan_groups where id = :id and deleted is null")
    suspend fun get(id: UUID): SubscriptionPlanGroup?

    @Query("select * from ecom.subscription_plan_groups where store_id = :storeId and deleted is null order by name")
    suspend fun getByStore(storeId: UUID): List<SubscriptionPlanGroup>

    @Query(
        """
        insert into ecom.subscription_plan_groups (store_id, key, name, description, payment_retries)
        values (:storeId, :key, :name, :description, :paymentRetries)
        returning *
        """,
    )
    suspend fun add(group: SubscriptionPlanGroup): SubscriptionPlanGroup
}
