package bosca.ecommerce.jobs

import bosca.ecommerce.service.CartService
import bosca.ecommerce.service.FulfillmentService
import bosca.ecommerce.service.ShipmentService
import bosca.ecommerce.service.SubscriptionService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * The runner sweep executors delegate to their service and log only when the sweep did work. Each
 * test exercises both the did-work (count > 0) and the nothing-to-do (count == 0) branches.
 */
class JobExecutorsTest {

    @Test
    fun `cart expiration sweep delegates and handles a non-empty sweep`() = runTest {
        val cartService = mockk<CartService>()
        coEvery { cartService.expireCarts() } returns 3
        CartExpirationSweepExecutor(cartService).execute()
        coVerify(exactly = 1) { cartService.expireCarts() }
    }

    @Test
    fun `cart expiration sweep handles an empty sweep`() = runTest {
        val cartService = mockk<CartService>()
        coEvery { cartService.expireCarts() } returns 0
        CartExpirationSweepExecutor(cartService).execute()
        coVerify(exactly = 1) { cartService.expireCarts() }
    }

    @Test
    fun `shipment tracking sweep delegates and handles polled shipments`() = runTest {
        val shipmentService = mockk<ShipmentService>()
        coEvery { shipmentService.sweepTracking() } returns 2
        ShipmentTrackingSweepExecutor(shipmentService).execute()
        coVerify(exactly = 1) { shipmentService.sweepTracking() }
    }

    @Test
    fun `shipment tracking sweep handles nothing in flight`() = runTest {
        val shipmentService = mockk<ShipmentService>()
        coEvery { shipmentService.sweepTracking() } returns 0
        ShipmentTrackingSweepExecutor(shipmentService).execute()
        coVerify(exactly = 1) { shipmentService.sweepTracking() }
    }

    @Test
    fun `subscription renewal sweep delegates and handles due subscriptions`() = runTest {
        val subscriptionService = mockk<SubscriptionService>()
        coEvery { subscriptionService.renewDue() } returns 5
        SubscriptionRenewalExecutor(subscriptionService).execute()
        coVerify(exactly = 1) { subscriptionService.renewDue() }
    }

    @Test
    fun `subscription renewal sweep handles nothing due`() = runTest {
        val subscriptionService = mockk<SubscriptionService>()
        coEvery { subscriptionService.renewDue() } returns 0
        SubscriptionRenewalExecutor(subscriptionService).execute()
        coVerify(exactly = 1) { subscriptionService.renewDue() }
    }

    @Test
    fun `inventory sync sweep delegates and handles updated rows`() = runTest {
        val fulfillmentService = mockk<FulfillmentService>()
        coEvery { fulfillmentService.syncInventory() } returns 4
        InventorySyncSweepExecutor(fulfillmentService).execute()
        coVerify(exactly = 1) { fulfillmentService.syncInventory() }
    }

    @Test
    fun `inventory sync sweep handles nothing to update`() = runTest {
        val fulfillmentService = mockk<FulfillmentService>()
        coEvery { fulfillmentService.syncInventory() } returns 0
        InventorySyncSweepExecutor(fulfillmentService).execute()
        coVerify(exactly = 1) { fulfillmentService.syncInventory() }
    }
}
