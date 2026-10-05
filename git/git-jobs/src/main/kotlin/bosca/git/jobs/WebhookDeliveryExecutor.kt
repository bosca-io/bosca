package bosca.git.jobs

import bosca.di.provide
import bosca.git.repository.WebhookRepository
import bosca.git.service.WebhookService
import bosca.queue.annotations.JobDefinition
import bosca.sharedqueue.jobs.AbstractJobExecutor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Delivers webhook payloads via HTTP POST with HMAC-SHA256 signatures.
 * The job queue framework handles retry policy — this executor performs
 * a single delivery attempt and throws on failure so the framework can
 * re-enqueue with backoff (10s, 60s, 300s).
 */
@JobDefinition(WebhookDeliveryJob::class, "git", "webhook-delivery")
class WebhookDeliveryExecutor : AbstractJobExecutor<WebhookDeliveryJob>(WebhookDeliveryJob.serializer()) {

    override suspend fun execute() {
        val job = getJobDefinition()
        deliver(job, provide(), provide())
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebhookDeliveryExecutor::class.java)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val httpClient = OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build()

        suspend fun deliver(
            job: WebhookDeliveryJob,
            webhookRepository: WebhookRepository,
            webhookService: WebhookService
        ) {
            val delivery = webhookRepository.findDeliveryById(job.deliveryId) ?: run {
                log.warn("Delivery {} not found, skipping", job.deliveryId)
                return
            }
            val webhook = webhookRepository.findById(delivery.webhookId) ?: run {
                log.warn("Webhook {} not found for delivery {}", delivery.webhookId, job.deliveryId)
                return
            }

            val signature = WebhookService.computeSignature(webhook.secret, delivery.payload)

            val request = Request.Builder()
                .url(webhook.url)
                .post(delivery.payload.toRequestBody(JSON_MEDIA_TYPE))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Bosca-Webhook/1.0")
                .header("X-Hub-Signature-256", signature)
                .header("X-Webhook-Event", delivery.event.name)
                .header("X-Webhook-Delivery", delivery.id.toString())
                .build()

            val response = httpClient.newCall(request).execute()
            try {
                val responseBody = response.body.string().take(1024)
                val statusCode = response.code

                webhookRepository.recordDeliveryResult(
                    delivery.id,
                    statusCode,
                    responseBody
                )

                if (!response.isSuccessful) {
                    log.warn("Webhook delivery {} failed: HTTP {}", delivery.id, statusCode)
                    throw RuntimeException("Webhook delivery failed: HTTP $statusCode")
                }

                log.info("Webhook delivery {} succeeded: HTTP {}", delivery.id, statusCode)
            } finally {
                response.close()
            }
        }
    }
}
