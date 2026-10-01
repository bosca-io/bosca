package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionPlan
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.service.SubscriptionPlanService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** EcomSubscription field wiring: scalar fields read the source; `plan` resolves via the plan service. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionControllerTest {

    private val planService = mockk<SubscriptionPlanService>()
    private val controller = SubscriptionController(planService)

    private val planId = UUID.random()
    private val subscription = Subscription(
        id = UUID.random(), storeId = UUID.random(), accountId = UUID.random(), planId = planId,
        planGroupId = UUID.random(), cartId = UUID.random(), status = SubscriptionStatus.ACTIVE,
        price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        paymentFailures = 2, renewals = 3,
    )

    @Test
    fun `scalar fields resolve from the source subscription`() {
        assertEquals(subscription.id, controller.id(subscription))
        assertEquals(SubscriptionStatus.ACTIVE, controller.status(subscription))
        assertEquals(Money.of("10.00"), controller.price(subscription))
        assertEquals(1, controller.interval(subscription))
        assertEquals(IntervalUnit.MONTHS, controller.intervalUnit(subscription))
        assertEquals(subscription.renews, controller.renews(subscription))
        assertEquals(subscription.expires, controller.expires(subscription))
        assertEquals(2, controller.paymentFailures(subscription))
        assertEquals(3, controller.renewals(subscription))
        assertEquals(subscription.cartId, controller.cartId(subscription))
        assertEquals(subscription.created, controller.created(subscription))
        assertEquals(subscription.modified, controller.modified(subscription))
    }

    @Test
    fun `plan resolves the snapshot plan via the plan service`() = runTest {
        val plan = SubscriptionPlan(
            id = planId, planGroupId = UUID.random(), storeId = UUID.random(), key = "monthly",
            name = "Monthly", price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS,
        )
        coEvery { planService.getPlan(planId) } returns plan

        assertEquals(planId, controller.plan(subscription).id)
    }

    @Test
    fun `plan throws when the plan is missing`() = runTest {
        coEvery { planService.getPlan(planId) } returns null
        assertFailsWith<IllegalStateException> { controller.plan(subscription) }
    }
}
