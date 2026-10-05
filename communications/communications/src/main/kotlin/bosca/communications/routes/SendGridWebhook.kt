package bosca.communications.routes

import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatusType
import bosca.communications.mailers.DeliveryTrackingArguments
import bosca.communications.mailers.sendgrid.getSendGridConfiguration
import bosca.communications.service.DeliveryTrackingService
import bosca.configuration.service.ConfigurationService
import bosca.routes.Route
import bosca.routes.annotations.RouteAuthentication
import bosca.routes.annotations.RouteController
import bosca.routes.annotations.RouteMethod
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.CancellationException

/**
 * Receives delivery event webhooks from SendGrid. The endpoint
 * accepts a JSON array of event objects and maps each to a
 * [DeliveryEvent] for persistence via [DeliveryTrackingService].
 *
 * SendGrid's Event Webhook sends events including: ``processed``,
 * ``delivered``, ``bounce``, ``deferred``, ``open``, ``click``,
 * ``dropped``, ``spamreport``, ``unsubscribe``, ``group_unsubscribe``.
 */
@RouteController(
    path = "/api/v1/webhooks/sendgrid",
    method = RouteMethod.POST,
    authentication = RouteAuthentication.NONE,
)
class SendGridWebhook(
    private val json: Json,
    private val deliveryTracking: DeliveryTrackingService,
    private val configurationService: ConfigurationService,
) : Route<Unit>() {

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext) {
        val result = handle(
            body = call.request.bodyText(),
            timestamp = call.request.header(SIGNATURE_TIMESTAMP_HEADER),
            signature = call.request.header(SIGNATURE_HEADER),
        )
        call.respond(result.status, result.body)
    }

    /**
     * Verifies and processes one SendGrid webhook batch.
     *
     * Storage failures return 500 so SendGrid retries the batch. Provider event ids make
     * those retries idempotent after any earlier events in the batch were committed.
     */
    internal suspend fun handle(body: String, timestamp: String?, signature: String?): WebhookResult {
        val verificationKey = configurationService.getSendGridConfiguration(json)?.webhookVerificationKey.orEmpty()
        if (verificationKey.isBlank()) {
            log.error("SendGrid webhook verification key is not configured")
            return WebhookResult(HttpStatusCode.ServiceUnavailable, "missing verification key")
        }
        if (!verifySignature(verificationKey, timestamp, body, signature)) {
            log.warn("SendGrid webhook signature verification failed")
            return WebhookResult(HttpStatusCode.Unauthorized, "invalid signature")
        }

        val events = try {
            json.decodeFromString<JsonArray>(body)
        } catch (e: IllegalArgumentException) {
            log.warn("Invalid SendGrid webhook JSON: {}", e.message)
            return WebhookResult(HttpStatusCode.BadRequest, "invalid payload")
        }

        return try {
            var processed = 0
            for (event in events) {
                if (processEvent(event)) processed++
            }
            log.info("Processed {} of {} SendGrid webhook events", processed, events.size)
            WebhookResult(HttpStatusCode.OK, "ok")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to persist SendGrid webhook batch", e)
            WebhookResult(HttpStatusCode.InternalServerError, "storage failure")
        }
    }

    private suspend fun processEvent(element: JsonElement): Boolean {
        val obj = element as? JsonObject ?: return false
        val sgEvent = obj["event"]?.jsonPrimitive?.contentOrNull ?: return false
        val status = mapEventToStatus(sgEvent) ?: return false
        val email = obj["email"]?.jsonPrimitive?.contentOrNull
        val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
        val sgStatus = obj["status"]?.jsonPrimitive?.contentOrNull
        val response = obj["response"]?.jsonPrimitive?.contentOrNull

        val messageId = extractMessageId(obj) ?: return false
        val recipientId = extractRecipientId(obj) ?: return false
        val createdAt = obj["timestamp"]?.jsonPrimitive?.longOrNull?.let {
            OffsetDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneOffset.UTC)
        } ?: OffsetDateTime.now()

        deliveryTracking.recordEvent(DeliveryEvent(
            providerEventId = obj["sg_event_id"]?.jsonPrimitive?.contentOrNull,
            messageId = messageId,
            recipientId = recipientId,
            status = status,
            providerEvent = sgEvent,
            errorCode = sgStatus,
            errorMessage = reason ?: response,
            metadata = element,
            createdAt = createdAt,
        ))

        if (status == DeliveryStatusType.BOUNCED && email != null) {
            val bounceType = obj["type"]?.jsonPrimitive?.contentOrNull
            if (bounceType == "bounce") {
                deliveryTracking.suppress(email, "hard bounce", sgStatus)
            }
        }
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(SendGridWebhook::class.java)
        private const val SIGNATURE_HEADER = "X-Twilio-Email-Event-Webhook-Signature"
        private const val SIGNATURE_TIMESTAMP_HEADER = "X-Twilio-Email-Event-Webhook-Timestamp"

        private fun mapEventToStatus(event: String): DeliveryStatusType? = when (event) {
            "processed" -> DeliveryStatusType.SENT
            "delivered" -> DeliveryStatusType.DELIVERED
            "bounce" -> DeliveryStatusType.BOUNCED
            "deferred" -> DeliveryStatusType.DEFERRED
            "open" -> DeliveryStatusType.OPENED
            "click" -> DeliveryStatusType.CLICKED
            "dropped" -> DeliveryStatusType.DROPPED
            "spamreport" -> DeliveryStatusType.SPAM_REPORT
            "unsubscribe", "group_unsubscribe" -> DeliveryStatusType.UNSUBSCRIBED
            else -> null
        }

        private fun extractMessageId(obj: Map<String, JsonElement>): UUID? {
            val boscaId = obj[DeliveryTrackingArguments.MESSAGE_ID]?.jsonPrimitive?.contentOrNull
            val value = boscaId ?: obj["sg_message_id"]?.jsonPrimitive?.contentOrNull?.substringBefore('.')
            if (value == null) return null
            return try {
                UUID.parse(value)
            } catch (_: Exception) {
                null
            }
        }

        private fun extractRecipientId(obj: Map<String, JsonElement>): UUID? {
            val id = obj["bosca_recipient_id"]?.jsonPrimitive?.contentOrNull ?: return null
            return try {
                UUID.parse(id)
            } catch (_: Exception) {
                null
            }
        }

        internal fun verifySignature(
            publicKey: String,
            timestamp: String?,
            body: String,
            signature: String?,
        ): Boolean {
            if (timestamp == null || signature == null) return false
            return try {
                val keyBytes = Base64.getDecoder().decode(publicKey)
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
                val verifier = Signature.getInstance("SHA256withECDSA")
                verifier.initVerify(key)
                verifier.update(timestamp.toByteArray(Charsets.UTF_8))
                verifier.update(body.toByteArray(Charsets.UTF_8))
                verifier.verify(Base64.getDecoder().decode(signature))
            } catch (_: Exception) {
                false
            }
        }
    }

    internal data class WebhookResult(val status: HttpStatusCode, val body: String)
}
