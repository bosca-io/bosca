package bosca.ecommerce.jobs

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/** Payload for the periodic subscription-renewal sweep (no parameters — the sweep drains all due). */
@Serializable
class SubscriptionRenewalJob : IJobDefinition
