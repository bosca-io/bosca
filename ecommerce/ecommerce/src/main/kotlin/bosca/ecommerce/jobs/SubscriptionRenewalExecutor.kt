package bosca.ecommerce.jobs

import bosca.ecommerce.configuration.JobQueueNames
import bosca.ecommerce.service.SubscriptionService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import org.slf4j.LoggerFactory

/**
 * Runs the subscription-renewal sweep on bosca-runner: charges each due subscription's saved method
 * and advances (or fails-counts) it. Registered as a cron-scheduled job by
 * `EcommerceScheduledJobsInstaller`.
 */
@JobDefinition(
    definition = SubscriptionRenewalJob::class,
    queue = JobQueueNames.ecomJobQueue,
    name = SubscriptionRenewalExecutor.NAME,
    displayName = "Ecommerce Subscription Renewals",
)
class SubscriptionRenewalExecutor(
    private val subscriptionService: SubscriptionService,
) : AbstractJobExecutor<SubscriptionRenewalJob>(SubscriptionRenewalJob.serializer()) {

    override suspend fun execute() {
        val processed = subscriptionService.renewDue()
        if (processed > 0) log.info("subscription renewal sweep processed {} subscriptions", processed)
    }

    companion object {
        const val NAME = "ecom-subscription-renewals"
        private val log = LoggerFactory.getLogger(SubscriptionRenewalExecutor::class.java)
    }
}
