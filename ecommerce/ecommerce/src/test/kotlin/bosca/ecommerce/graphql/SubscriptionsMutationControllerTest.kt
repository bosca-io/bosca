package bosca.ecommerce.graphql

import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.StandardSubscriptionExtras
import bosca.ecommerce.model.SubscribeInput
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
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Subscriptions collection mutations: subscribe is admin-gated; subscription(id) opens the id namespace. */
@OptIn(ExperimentalUuidApi::class)
class SubscriptionsMutationControllerTest {

    private val subscriptionService = mockk<SubscriptionService>()
    private val groups = mockk<GroupEvaluator>(relaxed = true)
    private val auth = mockk<AuthenticationContext>()
    private val controller = SubscriptionsMutationController(subscriptionService, groups)

    private val storeId = UUID.random()
    private val accountId = UUID.random()
    private val planId = UUID.random()
    private val subId = UUID.random()

    @BeforeTest
    fun setup() {
        every { auth.principal() } returns null
    }

    private fun input() = SubscribeInput(storeId = storeId, accountId = accountId, planId = planId)

    private fun subscription() = Subscription(
        id = subId, storeId = storeId, accountId = accountId, planId = planId, planGroupId = UUID.random(),
        status = SubscriptionStatus.ACTIVE, price = Money.of("10.00"), interval = 1,
        intervalUnit = IntervalUnit.MONTHS, extras = StandardSubscriptionExtras(),
    )

    @Test
    fun `subscribe gates on admin then subscribes via the service`() = runTest {
        coEvery { subscriptionService.subscribe(input(), null) } returns subscription()

        val result = controller.subscribe(auth, input())

        assertEquals(subId, result.id)
        verify(exactly = 1) { groups.verifyHasGroup(auth, ECOM_ADMINISTRATOR_GROUP) }
        coVerify(exactly = 1) { subscriptionService.subscribe(input(), null) }
    }

    @Test
    fun `subscribe forwards the non-null principal id to the service`() = runTest {
        val principalId = UUID.random()
        every { auth.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), emptyList())
        coEvery { subscriptionService.subscribe(input(), principalId) } returns subscription()

        controller.subscribe(auth, input())

        coVerify(exactly = 1) { subscriptionService.subscribe(input(), principalId) }
    }

    @Test
    fun `subscription accessor returns the id-scoped mutation namespace`() {
        assertEquals(subId, controller.subscription(subId).id)
    }
}
