package bosca.ecommerce.jobs

import bosca.ecommerce.configuration.JobQueueNames
import bosca.ecommerce.service.ShipmentService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Runs the shipment-tracking sweep on bosca-runner: polls each in-flight shipment's carrier for its
 * latest tracking reading and advances the shipment's status (delivered/in-transit/…). Registered as
 * a cron-scheduled job by `EcommerceScheduledJobsInstaller`; the scheduler enqueues it onto the ecom
 * queue, where this executor consumes it.
 */
@JobDefinition(
    definition = ShipmentTrackingSweepJob::class,
    queue = JobQueueNames.ecomJobQueue,
    name = ShipmentTrackingSweepExecutor.NAME,
    displayName = "Ecommerce Shipment Tracking Sweep",
)
class ShipmentTrackingSweepExecutor(
    private val shipmentService: ShipmentService,
) : AbstractJobExecutor<ShipmentTrackingSweepJob>(ShipmentTrackingSweepJob.serializer()) {

    override suspend fun execute() {
        val polled = shipmentService.sweepTracking()
        if (polled > 0) log.info("shipment tracking sweep polled {} in-flight shipments", polled)
    }

    companion object {
        const val NAME = "ecom-shipment-tracking-sweep"
        private val log = LoggerFactory.getLogger(ShipmentTrackingSweepExecutor::class.java)
    }
}
