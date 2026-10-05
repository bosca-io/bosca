package bosca.ecommerce.installer

import bosca.ecommerce.jobs.CartExpirationSweepExecutor
import bosca.ecommerce.jobs.CartExpirationSweepJob
import bosca.ecommerce.jobs.InventorySyncSweepExecutor
import bosca.ecommerce.jobs.InventorySyncSweepJob
import bosca.ecommerce.jobs.ShipmentTrackingSweepExecutor
import bosca.ecommerce.jobs.ShipmentTrackingSweepJob
import bosca.ecommerce.jobs.SubscriptionRenewalExecutor
import bosca.ecommerce.jobs.SubscriptionRenewalJob
import bosca.installer.model.PackageInstallation
import bosca.installer.model.PackageInstallationVersion
import bosca.installer.service.PackageInstaller
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.service.SchedulerService
import bosca.serialization.UUID
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Registers the ecommerce periodic jobs as cron-scheduled jobs (idempotently — only creates a job
 * whose [ScheduledJobInput.jobName] is not already registered). The platform `SchedulerRunner`
 * evaluates these on its cadence and enqueues them onto the ecom queue.
 */
class EcommerceScheduledJobsInstaller(
    private val schedulerService: SchedulerService,
    private val json: Json,
) : PackageInstaller {

    // Bumped 1.0.0 -> 1.1.0 to re-run install() on already-installed systems and register the new
    // inventory-sync sweep (the matching outer gate is a 1.1.0 PackageInstallationVersion in Configuration).
    override val version: String = "1.1.0"

    override suspend fun install(installation: PackageInstallation, version: PackageInstallationVersion) {
        val existing = schedulerService.getJobs().map { it.jobName }.toSet()
        jobs.forEach { job ->
            if (job.jobName in existing) {
                log.info("scheduled job '{}' already exists, skipping", job.jobName)
                return@forEach
            }
            log.info("creating scheduled job '{}'", job.jobName)
            schedulerService.createJob(job, createdBy = UUID.NIL)
        }
    }

    private val jobs = listOf(
        ScheduledJobInput(
            name = "Cart Expiration Sweep",
            description = "Reclaims expired open carts, releasing their inventory holds",
            jobName = CartExpirationSweepExecutor.NAME,
            jobParameters = json.encodeToJsonElement(CartExpirationSweepJob.serializer(), CartExpirationSweepJob()),
            cronExpression = "* * * * *",
            enabled = false,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1,
        ),
        ScheduledJobInput(
            name = "Subscription Renewals",
            description = "Charges due subscriptions' saved methods and advances or fail-counts them",
            jobName = SubscriptionRenewalExecutor.NAME,
            jobParameters = json.encodeToJsonElement(SubscriptionRenewalJob.serializer(), SubscriptionRenewalJob()),
            cronExpression = "0 * * * *",
            enabled = false,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1,
        ),
        ScheduledJobInput(
            name = "Shipment Tracking Sweep",
            description = "Polls in-flight shipments' carriers for tracking updates (in transit, delivered, …)",
            jobName = ShipmentTrackingSweepExecutor.NAME,
            jobParameters = json.encodeToJsonElement(ShipmentTrackingSweepJob.serializer(), ShipmentTrackingSweepJob()),
            cronExpression = "*/15 * * * *",
            enabled = false,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1,
        ),
        ScheduledJobInput(
            name = "Inventory Sync Sweep",
            description = "Pulls on-hand quantities from each fulfillment center's inventory connector, honoring its sync interval",
            jobName = InventorySyncSweepExecutor.NAME,
            jobParameters = json.encodeToJsonElement(InventorySyncSweepJob.serializer(), InventorySyncSweepJob()),
            cronExpression = "* * * * *",
            enabled = false,
            allowConcurrent = false,
            catchUp = false,
            maxCatchUp = 1,
        ),
    )

    companion object {
        private val log = LoggerFactory.getLogger(EcommerceScheduledJobsInstaller::class.java)
    }
}
