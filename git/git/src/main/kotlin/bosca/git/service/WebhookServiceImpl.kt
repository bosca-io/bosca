package bosca.git.service

import bosca.git.model.Webhook
import bosca.git.model.WebhookDelivery
import bosca.git.model.WebhookEvent
import bosca.git.repository.WebhookRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URI

/**
 * Manages webhook persistence and enqueues delivery records for downstream
 * HTTP dispatch by the WebhookDeliveryExecutor job.
 */
@ServiceImplementation
class WebhookServiceImpl(
    private val webhookRepository: WebhookRepository
) : WebhookService {

    private val log = LoggerFactory.getLogger(WebhookServiceImpl::class.java)

    override suspend fun findByRepository(repositoryId: UUID): List<Webhook> {
        return webhookRepository.findByRepository(repositoryId)
    }

    override suspend fun findById(id: UUID): Webhook? {
        return webhookRepository.findById(id)
    }

    override suspend fun create(webhook: Webhook): Webhook {
        validateWebhookUrl(webhook.url)
        return webhookRepository.create(webhook)
    }

    override suspend fun update(webhook: Webhook): Webhook {
        validateWebhookUrl(webhook.url)
        return webhookRepository.update(webhook)
            ?: throw NoSuchElementException("Webhook not found: ${webhook.id}")
    }

    override suspend fun delete(id: UUID) {
        webhookRepository.delete(id)
    }

    override suspend fun getDeliveries(webhookId: UUID, offset: Long, limit: Int): List<WebhookDelivery> {
        return webhookRepository.findDeliveries(webhookId, offset, limit)
    }

    private fun validateWebhookUrl(url: String) {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid webhook URL")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw IllegalArgumentException("Webhook URL must use HTTP or HTTPS")
        }
        val host = uri.host?.lowercase()
            ?: throw IllegalArgumentException("Webhook URL must have a host")
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0") {
            throw IllegalArgumentException("Webhook URL must not target localhost")
        }
        if (host == "metadata.google.internal" || host.startsWith("169.254.")) {
            throw IllegalArgumentException("Webhook URL must not target cloud metadata services")
        }
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            return
        }
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) {
            throw IllegalArgumentException("Webhook URL must not target private or loopback addresses")
        }
    }

    override suspend fun dispatch(repositoryId: UUID, event: WebhookEvent, payload: String) {
        val webhooks = webhookRepository.findActiveByRepository(repositoryId)
        for (webhook in webhooks) {
            if (event !in webhook.events) continue
            webhookRepository.createDelivery(
                WebhookDelivery(
                    webhookId = webhook.id,
                    event = event,
                    payload = payload
                )
            )
            log.debug("Queued delivery for webhook {} event {}", webhook.id, event)
        }
    }
}
