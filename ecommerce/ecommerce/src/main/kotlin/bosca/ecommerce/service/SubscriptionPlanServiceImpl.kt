package bosca.ecommerce.service

import bosca.db.transaction
import bosca.ecommerce.model.PlanGroupInput
import bosca.ecommerce.model.PlanInput
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.repository.SubscriptionPlanGroupRepository
import bosca.ecommerce.repository.SubscriptionPlanRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

/** Subscription plan groups + plans. Creates are audited. */
@ServiceImplementation
class SubscriptionPlanServiceImpl(
    private val planGroupRepository: SubscriptionPlanGroupRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val auditService: EcomAuditService,
) : SubscriptionPlanService {

    override suspend fun getGroup(id: UUID): SubscriptionPlanGroup? = planGroupRepository.get(id)

    override suspend fun getGroupsByStore(storeId: UUID): List<SubscriptionPlanGroup> = planGroupRepository.getByStore(storeId)

    override suspend fun getPlan(id: UUID): SubscriptionPlan? = planRepository.get(id)

    override suspend fun getPlanByKey(storeId: UUID, key: String): SubscriptionPlan? = planRepository.getByKey(storeId, key)

    override suspend fun getPlansByGroup(planGroupId: UUID): List<SubscriptionPlan> = planRepository.getByGroup(planGroupId)

    override suspend fun createGroup(input: PlanGroupInput, principalId: UUID?): SubscriptionPlanGroup = transaction {
        val group = planGroupRepository.add(
            SubscriptionPlanGroup(storeId = input.storeId, key = input.key, name = input.name, description = input.description, paymentRetries = input.paymentRetries),
        )
        auditService.record("subscription_plan_group", group.id, "created", serializer = SubscriptionPlanGroup.serializer(), after = group, principalId = principalId, storeId = group.storeId)
        group
    }

    override suspend fun createPlan(input: PlanInput, principalId: UUID?): SubscriptionPlan = transaction {
        val plan = planRepository.add(
            SubscriptionPlan(
                planGroupId = input.planGroupId, storeId = input.storeId, key = input.key, name = input.name,
                description = input.description, price = input.price, interval = input.interval, intervalUnit = input.intervalUnit,
                configuration = input.configuration,
            ),
        )
        auditService.record("subscription_plan", plan.id, "created", serializer = SubscriptionPlan.serializer(), after = plan, principalId = principalId, storeId = plan.storeId)
        plan
    }
}
