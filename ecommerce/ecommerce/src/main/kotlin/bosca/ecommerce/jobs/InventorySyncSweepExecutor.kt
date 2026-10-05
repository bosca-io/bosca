package bosca.ecommerce.jobs

import bosca.ecommerce.configuration.JobQueueNames
import bosca.ecommerce.service.FulfillmentService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Runs the inventory-sync sweep on bosca-runner: for each fulfillment center due for a refresh, pulls
 * on-hand quantities from its inventory connector and updates the changed rows. Registered as a
 * cron-scheduled job by `EcommerceScheduledJobsInstaller`; the per-center `syncIntervalSeconds` gates
 * which centers actually sync on a given tick.
 */
@JobDefinition(
    definition = InventorySyncSweepJob::class,
    queue = JobQueueNames.ecomJobQueue,
    name = InventorySyncSweepExecutor.NAME,
    displayName = "Ecommerce Inventory Sync Sweep",
)
class InventorySyncSweepExecutor(
    private val fulfillmentService: FulfillmentService,
) : AbstractJobExecutor<InventorySyncSweepJob>(InventorySyncSweepJob.serializer()) {

    override suspend fun execute() {
        val updated = fulfillmentService.syncInventory()
        if (updated > 0) log.info("inventory sync sweep updated {} inventory rows", updated)
    }

    companion object {
        const val NAME = "ecom-inventory-sync-sweep"
        private val log = LoggerFactory.getLogger(InventorySyncSweepExecutor::class.java)
    }
}
