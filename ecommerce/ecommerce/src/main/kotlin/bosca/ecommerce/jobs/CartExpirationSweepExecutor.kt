package bosca.ecommerce.jobs

import bosca.ecommerce.configuration.JobQueueNames
import bosca.ecommerce.service.CartService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Runs the cart-expiration sweep on bosca-runner: reclaims expired OPEN carts, releasing their
 * inventory holds (replaces the legacy in-process `CartCleanup` scheduler task). Registered as a
 * cron-scheduled job by `EcommerceScheduledJobsInstaller`; the platform scheduler enqueues it onto
 * the ecom queue, where this executor consumes it.
 */
@JobDefinition(
    definition = CartExpirationSweepJob::class,
    queue = JobQueueNames.ecomJobQueue,
    name = CartExpirationSweepExecutor.NAME,
    displayName = "Ecommerce Cart Expiration Sweep",
)
class CartExpirationSweepExecutor(
    private val cartService: CartService,
) : AbstractJobExecutor<CartExpirationSweepJob>(CartExpirationSweepJob.serializer()) {

    override suspend fun execute() {
        val expired = cartService.expireCarts()
        if (expired > 0) log.info("cart expiration sweep reclaimed {} carts", expired)
    }

    companion object {
        const val NAME = "ecom-cart-expiration-sweep"
        private val log = LoggerFactory.getLogger(CartExpirationSweepExecutor::class.java)
    }
}
