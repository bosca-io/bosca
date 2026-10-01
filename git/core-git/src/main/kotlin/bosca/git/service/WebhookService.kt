package bosca.git.service

import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.model.WebhookEvent
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Manages webhook CRUD and dispatches event deliveries. Delivery is performed
 * asynchronously — this service enqueues delivery records and the
 * WebhookDeliveryExecutor in the jobs module handles the actual HTTP POST
 * with retry logic.
 */
interface WebhookService : Service {

    /**
     * Retrieves all webhooks configured for a repository.
     */
    suspend fun findByRepository(repositoryId: UUID): List<Webhook>

    /**
     * Retrieves a webhook by its unique identifier.
     */
    suspend fun findById(id: UUID): Webhook?

    /**
     * Registers a new webhook and returns the persisted entity.
     */
    suspend fun create(webhook: Webhook): Webhook

    /**
     * Updates an existing webhook configuration. Throws if the webhook does not exist.
     */
    suspend fun update(webhook: Webhook): Webhook

    /**
     * Removes a webhook and all its delivery history.
     */
    suspend fun delete(id: UUID)

    /**
     * Retrieves delivery history for a webhook with pagination.
     */
    suspend fun getDeliveries(webhookId: UUID, offset: Long, limit: Int): List<WebhookDelivery>

    /**
     * Dispatches an event to all active webhooks on a repository that subscribe to
     * the given event type. Creates delivery records for each matching webhook.
     */
    suspend fun dispatch(repositoryId: UUID, event: WebhookEvent, payload: String)

    companion object {
        /**
         * Computes the HMAC SHA-256 signature for a webhook payload, formatted as
         * `sha256=<hex>` for the `X-Hub-Signature-256` header.
         */
        fun computeSignature(secret: String, payload: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            val hash = mac.doFinal(payload.toByteArray())
            return "sha256=" + hash.joinToString("") { "%02x".format(it) }
        }
    }
}
