package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.StandardSubscriptionExtras
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.service.SubscriptionService
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
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Subscription mutations: each gates on the ecom-admin group, then delegates to the service. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionMutationControllerTest {

    private val subscriptionService = mockk<SubscriptionService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = SubscriptionMutationController(subscriptionService, groups)

    private val subId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun subscription(status: SubscriptionStatus = SubscriptionStatus.ACTIVE) = Subscription(
        id = subId, storeId = UUID.random(), accountId = UUID.random(), planId = UUID.random(),
        planGroupId = UUID.random(), status = status, price = Money.of("10.00"), interval = 1,
        intervalUnit = IntervalUnit.MONTHS, extras = StandardSubscriptionExtras(),
    )

    @Test
    fun `cancel gates on admin then cancels via the service`() = runTest {
        coEvery { subscriptionService.cancel(subId, null) } returns subscription(SubscriptionStatus.CANCELLED)

        val result = controller.cancel(auth, SubscriptionMutation(subId))

        assertEquals(SubscriptionStatus.CANCELLED, result.status)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { subscriptionService.cancel(subId, null) }
    }

    @Test
    fun `changePlan gates on admin, inherits current extras, then changes plans via the service`() = runTest {
        val newPlanId = UUID.random()
        val current = subscription()
        val successor = subscription().copy(id = UUID.random())
        coEvery { subscriptionService.get(subId) } returns current
        coEvery { subscriptionService.changePlans(subId, newPlanId, current.extras, null) } returns successor

        val result = controller.changePlan(auth, SubscriptionMutation(subId), newPlanId)

        assertEquals(successor.id, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { subscriptionService.changePlans(subId, newPlanId, current.extras, null) }
    }

    @Test
    fun `cancel forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { subscriptionService.cancel(subId, principalId) } returns subscription(SubscriptionStatus.CANCELLED)

        controller.cancel(auth, SubscriptionMutation(subId))

        coVerify(exactly = 1) { subscriptionService.cancel(subId, principalId) }
    }

    @Test
    fun `changePlan forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        val newPlanId = UUID.random()
        val current = subscription()
        val successor = subscription().copy(id = UUID.random())
        coEvery { subscriptionService.get(subId) } returns current
        coEvery { subscriptionService.changePlans(subId, newPlanId, current.extras, principalId) } returns successor

        controller.changePlan(auth, SubscriptionMutation(subId), newPlanId)

        coVerify(exactly = 1) { subscriptionService.changePlans(subId, newPlanId, current.extras, principalId) }
    }

    @Test
    fun `changePlan throws when the subscription is missing`() = runTest {
        coEvery { subscriptionService.get(subId) } returns null
        assertFailsWith<IllegalStateException> {
            controller.changePlan(auth, SubscriptionMutation(subId), UUID.random())
        }
    }
}
