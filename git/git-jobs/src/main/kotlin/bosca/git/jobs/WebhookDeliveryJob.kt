package bosca.git.jobs

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Job payload for delivering a pending webhook. The executor fetches the
 * delivery record, performs the HTTP POST with HMAC signature, and records
 * the result. Retries up to 3 times with exponential backoff (10s, 60s, 300s).
 */
@Serializable
data class WebhookDeliveryJob(
    val deliveryId: UUID
) : IJobDefinition
