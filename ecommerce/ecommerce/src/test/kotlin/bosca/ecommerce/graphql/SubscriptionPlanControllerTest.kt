package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PlanStatus
import bosca.ecommerce.model.StandardPlanConfiguration
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** SubscriptionPlan field wiring: scalar fields read the source; `group` resolves via the plan service. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionPlanControllerTest {

    private val planService = mockk<SubscriptionPlanService>()
    private val controller = SubscriptionPlanController(planService)

    private val groupId = UUID.random()
    private val plan = SubscriptionPlan(
        id = UUID.random(), planGroupId = groupId, storeId = UUID.random(), key = "monthly",
        name = "Monthly", description = "Every month", status = PlanStatus.ACTIVE,
        price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        configuration = StandardPlanConfiguration,
    )

    @Test
    fun `scalar fields resolve from the source plan`() {
        assertEquals(plan.id, controller.id(plan))
        assertEquals("monthly", controller.key(plan))
        assertEquals("Monthly", controller.name(plan))
        assertEquals("Every month", controller.description(plan))
        assertEquals(PlanStatus.ACTIVE, controller.status(plan))
        assertEquals(Money.of("9.99"), controller.price(plan))
        assertEquals(1, controller.interval(plan))
        assertEquals(IntervalUnit.MONTHS, controller.intervalUnit(plan))
        assertEquals(StandardPlanConfiguration, controller.configuration(plan))
        assertEquals(plan.expires, controller.expires(plan))
        assertEquals(plan.created, controller.created(plan))
        assertEquals(plan.modified, controller.modified(plan))
    }

    @Test
    fun `group resolves the plan group via the plan service`() = runTest {
        val group = SubscriptionPlanGroup(id = groupId, storeId = UUID.random(), key = "pro", name = "Pro")
        coEvery { planService.getGroup(groupId) } returns group

        assertEquals(groupId, controller.group(plan).id)
    }

    @Test
    fun `group throws when the group is missing`() = runTest {
        coEvery { planService.getGroup(groupId) } returns null
        assertFailsWith<IllegalStateException> { controller.group(plan) }
    }
}
