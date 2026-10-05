package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.PlanGroupInput
import bosca.ecommerce.model.PlanInput
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionPlanGroup
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Plans collection mutations: each gates on the ecom-admin group, then delegates to the plan service. */
@OptIn(ExperimentalUuidApi::class)
class PlansMutationControllerTest {

    private val planService = mockk<SubscriptionPlanService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = PlansMutationController(planService, groups)

    private val storeId = UUID.random()
    private val groupId = UUID.random()
    private val planId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun groupInput() = PlanGroupInput(storeId = storeId, key = "pro", name = "Pro")

    private fun planInput() = PlanInput(
        planGroupId = groupId, storeId = storeId, key = "pro-monthly", name = "Pro Monthly",
        price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
    )

    @Test
    fun `addGroup gates on admin then creates the group via the service`() = runTest {
        val group = SubscriptionPlanGroup(id = groupId, storeId = storeId, key = "pro", name = "Pro")
        coEvery { planService.createGroup(groupInput(), null) } returns group

        val result = controller.addGroup(auth, groupInput())

        assertEquals(groupId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { planService.createGroup(groupInput(), null) }
    }

    @Test
    fun `addPlan gates on admin then creates the plan via the service`() = runTest {
        val plan = SubscriptionPlan(
            id = planId, planGroupId = groupId, storeId = storeId, key = "pro-monthly", name = "Pro Monthly",
            price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        )
        coEvery { planService.createPlan(planInput(), null) } returns plan

        val result = controller.addPlan(auth, planInput())

        assertEquals(planId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { planService.createPlan(planInput(), null) }
    }

    @Test
    fun `addGroup forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val group = SubscriptionPlanGroup(id = groupId, storeId = storeId, key = "pro", name = "Pro")
        coEvery { planService.createGroup(groupInput(), principalId) } returns group

        controller.addGroup(auth, groupInput())

        coVerify(exactly = 1) { planService.createGroup(groupInput(), principalId) }
    }

    @Test
    fun `addPlan forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val plan = SubscriptionPlan(
            id = planId, planGroupId = groupId, storeId = storeId, key = "pro-monthly", name = "Pro Monthly",
            price = Money.of("9.99"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        )
        coEvery { planService.createPlan(planInput(), principalId) } returns plan

        controller.addPlan(auth, planInput())

        coVerify(exactly = 1) { planService.createPlan(planInput(), principalId) }
    }
}
