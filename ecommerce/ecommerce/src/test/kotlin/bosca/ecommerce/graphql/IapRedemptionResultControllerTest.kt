package bosca.ecommerce.graphql

import bosca.ecommerce.model.IapRedemptionResult
import bosca.ecommerce.model.IapRedemptionStatus
import bosca.ecommerce.model.IntervalUnit
import bosca.ecommerce.model.Money
import bosca.ecommerce.model.Subscription
import bosca.ecommerce.model.SubscriptionStatus
import bosca.ecommerce.service.SubscriptionService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.coroutines.test.runTest

/** Type-wiring for IapRedemptionResult: scalar fields read through, the subscription resolves by id. */
@OptIn(ExperimentalUuidApi::class)
class IapRedemptionResultControllerTest {

    private val subscriptionService = mockk<SubscriptionService>()
    private val controller = IapRedemptionResultController(subscriptionService)

    private val subId = UUID.random()

    private fun subscription() = Subscription(
        id = subId, storeId = UUID.random(), accountId = UUID.random(), planId = UUID.random(), planGroupId = UUID.random(),
        status = SubscriptionStatus.ACTIVE, price = Money.of("10.00"), interval = 1, intervalUnit = IntervalUnit.MONTHS, external = true,
    )

    @Test
    fun `status and transactionId read straight through`() {
        val result = IapRedemptionResult(IapRedemptionStatus.GRANTED, subId, "tx-1")
        assertEquals(IapRedemptionStatus.GRANTED, controller.status(result))
        assertEquals("tx-1", controller.transactionId(result))
    }

    @Test
    fun `subscription resolves the granted subscription by id`() = runTest {
        coEvery { subscriptionService.get(subId) } returns subscription()
        val result = IapRedemptionResult(IapRedemptionStatus.GRANTED, subId, "tx-1")

        assertEquals(subId, controller.subscription(result)?.id)
        coVerify(exactly = 1) { subscriptionService.get(subId) }
    }

    @Test
    fun `subscription is null when no subscription was granted`() = runTest {
        val result = IapRedemptionResult(IapRedemptionStatus.NOT_FOUND, subscriptionId = null, transactionId = null)
        assertNull(controller.subscription(result))
        coVerify(exactly = 0) { subscriptionService.get(any()) }
    }
}
