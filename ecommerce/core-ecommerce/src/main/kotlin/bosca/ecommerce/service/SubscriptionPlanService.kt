package bosca.ecommerce.service

import bosca.ecommerce.model.PlanGroupInput
import bosca.ecommerce.model.PlanInput
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.serialization.UUID
import bosca.service.Service

/** Subscription plan groups + plans (admin CRUD). */
interface SubscriptionPlanService : Service {

    suspend fun getGroup(id: UUID): SubscriptionPlanGroup?

    /** Every plan group for a store, ordered by name. */
    suspend fun getGroupsByStore(storeId: UUID): List<SubscriptionPlanGroup>

    suspend fun createGroup(input: PlanGroupInput, principalId: UUID?): SubscriptionPlanGroup

    suspend fun getPlan(id: UUID): SubscriptionPlan?

    suspend fun getPlanByKey(storeId: UUID, key: String): SubscriptionPlan?

    /** Every plan within a group, ordered by price. */
    suspend fun getPlansByGroup(planGroupId: UUID): List<SubscriptionPlan>

    suspend fun createPlan(input: PlanInput, principalId: UUID?): SubscriptionPlan
}
