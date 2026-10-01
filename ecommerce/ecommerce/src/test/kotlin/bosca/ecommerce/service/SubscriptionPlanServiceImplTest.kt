package bosca.ecommerce.service

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PlanGroupInput
import bosca.ecommerce.model.PlanInput
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.repository.SubscriptionPlanGroupRepository
import bosca.ecommerce.repository.SubscriptionPlanRepository
import bosca.serialization.UUID
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
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/**subscription plan/group orchestration — reads delegate; group/plan creates are audited store-scoped. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionPlanServiceImplTest {

    private val planGroupRepository = mockk<SubscriptionPlanGroupRepository>()
    private val planRepository = mockk<SubscriptionPlanRepository>()
    private val auditService = mockk<EcomAuditService>(relaxed = true)
    private lateinit var service: SubscriptionPlanServiceImpl

    private val storeId = UUID.random()
    private val groupId = UUID.random()
    private val planId = UUID.random()

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers { firstArg<suspend () -> Any?>().invoke() }
        service = SubscriptionPlanServiceImpl(planGroupRepository, planRepository, auditService)
    }

    @AfterTest
    fun teardown() = unmockkAll()

    private fun group() = SubscriptionPlanGroup(id = groupId, storeId = storeId, key = "pro", name = "Pro")
    private fun plan() = SubscriptionPlan(
        id = planId, planGroupId = groupId, storeId = storeId, key = "pro-monthly", name = "Pro Monthly",
        price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
    )

    @Test
    fun `group and plan reads delegate to the repositories`() = runTest {
        coEvery { planGroupRepository.get(groupId) } returns group()
        coEvery { planGroupRepository.getByStore(storeId) } returns listOf(group())
        coEvery { planRepository.get(planId) } returns plan()
        coEvery { planRepository.getByKey(storeId, "pro-monthly") } returns plan()
        coEvery { planRepository.getByGroup(groupId) } returns listOf(plan())

        assertEquals(groupId, service.getGroup(groupId)?.id)
        assertEquals(1, service.getGroupsByStore(storeId).size)
        assertEquals(planId, service.getPlan(planId)?.id)
        assertEquals("pro-monthly", service.getPlanByKey(storeId, "pro-monthly")?.key)
        assertEquals(1, service.getPlansByGroup(groupId).size)
    }

    @Test
    fun `createGroup persists the group from the input and audits store-scoped`() = runTest {
        val added = slot<SubscriptionPlanGroup>()
        coEvery { planGroupRepository.add(capture(added)) } answers { added.captured.copy(id = groupId) }

        val result = service.createGroup(PlanGroupInput(storeId = storeId, key = "pro", name = "Pro", paymentRetries = 5), principalId = null)

        assertEquals(groupId, result.id)
        assertEquals(storeId, added.captured.storeId)
        assertEquals(5, added.captured.paymentRetries)
        coVerify(exactly = 1) {
            auditService.record<SubscriptionPlanGroup>(entityType = "subscription_plan_group", entityId = groupId, action = "created", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = storeId, details = any())
        }
    }

    @Test
    fun `createPlan persists the plan from the input and audits store-scoped`() = runTest {
        val added = slot<SubscriptionPlan>()
        coEvery { planRepository.add(capture(added)) } answers { added.captured.copy(id = planId) }

        val result = service.createPlan(
            PlanInput(
                planGroupId = groupId, storeId = storeId, key = "pro-monthly", name = "Pro Monthly",
                price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
            ),
            principalId = null,
        )

        assertEquals(planId, result.id)
        assertEquals(groupId, added.captured.planGroupId)
        assertEquals(Money.of("9.99"), added.captured.price)
        assertEquals(IntervalUnit.MONTHS, added.captured.intervalUnit)
        coVerify(exactly = 1) {
            auditService.record<SubscriptionPlan>(entityType = "subscription_plan", entityId = planId, action = "created", serializer = any(), before = any(), after = any(), principalId = any(), profileId = any(), storeId = storeId, details = any())
        }
    }
}
