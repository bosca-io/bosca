@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.communications.routes

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.communications.model.DeliveryEvent
import bosca.communications.model.DeliveryStatus
import bosca.communications.model.DeliveryStatusType
import bosca.communications.mailers.sendgrid.SendGridConfiguration
import bosca.communications.service.DeliveryTrackingService
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.db.ConnectionPool
import bosca.routes.configureCommunicationsRoutes
import bosca.security.service.AuthenticationProviders
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendGridWebhookTest {

    private lateinit var keyPair: KeyPair
    private lateinit var tracking: RecordingDeliveryTracking
    private lateinit var configurationService: ConfigurationService
    private lateinit var configurationId: UUID
    private lateinit var webhook: SendGridWebhook

    @BeforeTest
    fun setup() {
        keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        tracking = RecordingDeliveryTracking()
        configurationId = UUID.random()
        configurationService = mockk()
        coEvery { configurationService.getByKey(SendGridConfiguration.KEY) } returns Configuration(
            id = configurationId,
            key = SendGridConfiguration.KEY,
            description = "SendGrid Email Integration Configuration",
            public = false,
        )
        configureWebhookKey(Base64.getEncoder().encodeToString(keyPair.public.encoded))
        webhook = SendGridWebhook(
            Json { ignoreUnknownKeys = true },
            tracking,
            configurationService,
        )
    }

    private fun configureWebhookKey(key: String) {
        coEvery { configurationService.getValue(configurationId) } returns Json.encodeToJsonElement(
            SendGridConfiguration.serializer(),
            SendGridConfiguration(webhookVerificationKey = key),
        )
    }

    @Test
    fun `a valid signed provider event records the correlated Bosca delivery`() = runBlocking {
        val messageId = UUID.random()
        val recipientId = UUID.random()
        val body = """[{
          "event":"delivered",
          "sg_event_id":"provider-event-1",
          "sg_message_id":"provider-message-id",
          "bosca_message_id":"$messageId",
          "bosca_recipient_id":"$recipientId",
          "timestamp":1785326400
        }]""".trimIndent()
        val timestamp = "1785326401"

        val result = webhook.handle(body, timestamp, sign(timestamp, body))

        assertEquals(HttpStatusCode.OK, result.status)
        val event = tracking.recorded.single()
        assertEquals("provider-event-1", event.providerEventId)
        assertEquals(messageId, event.messageId)
        assertEquals(recipientId, event.recipientId)
        assertEquals(DeliveryStatusType.DELIVERED, event.status)
        assertEquals("2026-07-29T12:00Z", event.createdAt.toString())
    }

    @Test
    fun `an invalid signature is rejected without recording events`() = runBlocking {
        val result = webhook.handle("[]", "123", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))

        assertEquals(HttpStatusCode.Unauthorized, result.status)
        assertTrue(tracking.recorded.isEmpty())
    }

    @Test
    fun `a blank verification key fails closed`() = runBlocking {
        configureWebhookKey("")

        val result = webhook.handle("[]", null, null)

        assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
        assertTrue(tracking.recorded.isEmpty())
    }

    @Test
    fun `a missing SendGrid configuration fails closed`() = runBlocking {
        coEvery { configurationService.getByKey(SendGridConfiguration.KEY) } returns null

        val result = webhook.handle("[]", null, null)

        assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
        assertTrue(tracking.recorded.isEmpty())
    }

    @Test
    fun `storage failure returns 500 so SendGrid retries the batch`() = runBlocking {
        tracking.fail = true
        val messageId = UUID.random()
        val recipientId = UUID.random()
        val body = """[{
          "event":"delivered",
          "sg_event_id":"provider-event-2",
          "bosca_message_id":"$messageId",
          "bosca_recipient_id":"$recipientId"
        }]""".trimIndent()
        val timestamp = "1785326401"

        val result = webhook.handle(body, timestamp, sign(timestamp, body))

        assertEquals(HttpStatusCode.InternalServerError, result.status)
    }

    @Test
    fun `all provider event types map and hard bounces suppress the address`() = runBlocking {
        val messageId = UUID.random()
        val recipientId = UUID.random()
        val body = """[
          {"event":"processed","sg_message_id":"$messageId.provider","bosca_recipient_id":"$recipientId","response":"queued"},
          {"event":"delivered","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"bounce","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId","email":"hard@example.com","type":"bounce","status":"550","reason":"mailbox missing"},
          {"event":"deferred","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"open","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"click","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"dropped","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"spamreport","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"unsubscribe","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"group_unsubscribe","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"}
        ]""".trimIndent()
        val timestamp = "1785326401"

        val result = webhook.handle(body, timestamp, sign(timestamp, body))

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(
            listOf(
                DeliveryStatusType.SENT,
                DeliveryStatusType.DELIVERED,
                DeliveryStatusType.BOUNCED,
                DeliveryStatusType.DEFERRED,
                DeliveryStatusType.OPENED,
                DeliveryStatusType.CLICKED,
                DeliveryStatusType.DROPPED,
                DeliveryStatusType.SPAM_REPORT,
                DeliveryStatusType.UNSUBSCRIBED,
                DeliveryStatusType.UNSUBSCRIBED,
            ),
            tracking.recorded.map(DeliveryEvent::status),
        )
        assertEquals("queued", tracking.recorded.first().errorMessage)
        assertEquals(Triple("hard@example.com", "hard bounce", "550"), tracking.suppressions.single())
    }

    @Test
    fun `malformed and uncorrelated provider events are ignored`() = runBlocking {
        val messageId = UUID.random()
        val recipientId = UUID.random()
        val body = """[
          42,
          {},
          {"event":null},
          {"event":"unknown","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"},
          {"event":"delivered","bosca_recipient_id":"$recipientId"},
          {"event":"delivered","bosca_message_id":"not-a-uuid","bosca_recipient_id":"$recipientId"},
          {"event":"delivered","bosca_message_id":"$messageId"},
          {"event":"delivered","bosca_message_id":"$messageId","bosca_recipient_id":"not-a-uuid"},
          {"event":"delivered","bosca_message_id":null,"sg_message_id":"$messageId.provider","bosca_recipient_id":"$recipientId","timestamp":"invalid"},
          {"event":"delivered","bosca_message_id":null,"sg_message_id":null,"bosca_recipient_id":"$recipientId"},
          {"event":"delivered","bosca_message_id":"$messageId","bosca_recipient_id":null},
          {"event":"delivered","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId","email":null,"reason":null,"status":null,"response":null,"timestamp":null,"sg_event_id":null},
          {"event":"bounce","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId","email":"soft@example.com","type":"blocked"},
          {"event":"bounce","bosca_message_id":"$messageId","bosca_recipient_id":"$recipientId"}
        ]""".trimIndent()
        val timestamp = "1785326401"

        val result = webhook.handle(body, timestamp, sign(timestamp, body))

        assertEquals(HttpStatusCode.OK, result.status)
        assertEquals(4, tracking.recorded.size)
        assertTrue(tracking.suppressions.isEmpty())
    }

    @Test
    fun `signed malformed JSON returns bad request`() = runBlocking {
        val body = "{not-json"
        val timestamp = "1785326401"

        val result = webhook.handle(body, timestamp, sign(timestamp, body))

        assertEquals(HttpStatusCode.BadRequest, result.status)
    }

    @Test
    fun `storage cancellation is propagated`() = runBlocking {
        tracking.failure = CancellationException("stopping")
        val messageId = UUID.random()
        val recipientId = UUID.random()
        val body = """[{
          "event":"delivered",
          "bosca_message_id":"$messageId",
          "bosca_recipient_id":"$recipientId"
        }]""".trimIndent()
        val timestamp = "1785326401"

        assertFailsWith<CancellationException> {
            webhook.handle(body, timestamp, sign(timestamp, body))
        }
        Unit
    }

    @Test
    fun `signature verification rejects missing or malformed inputs`() {
        assertTrue(!SendGridWebhook.verifySignature("not-base64", "1", "[]", "not-base64"))
        assertTrue(!SendGridWebhook.verifySignature("not-base64", null, "[]", "signature"))
        assertTrue(!SendGridWebhook.verifySignature("not-base64", "1", "[]", null))
    }

    @Test
    fun `route execution reads signature headers and writes the handler response`() = runBlocking {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<ConnectionPool> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        val body = "[]"
        val timestamp = "1785326401"
        val signature = sign(timestamp, body)
        val call = mockk<ServerCall>(relaxed = true)
        coEvery { call.request.bodyText() } returns body
        every { call.request.header("X-Twilio-Email-Event-Webhook-Timestamp") } returns timestamp
        every { call.request.header("X-Twilio-Email-Event-Webhook-Signature") } returns signature

        try {
            webhook.execute(call)
            assertTrue(tracking.recorded.isEmpty())
        } finally {
            ProviderRegistry.clear()
        }
    }

    @Test
    fun `generated communications routes register the SendGrid webhook`() = runBlocking {
        ProviderRegistry.clear()
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        try {
            provides<SendGridWebhook>(singleton = true) { webhook }
            provides<AuthenticationProviders>(singleton = true) {
                AuthenticationProviders(emptyArray())
            }

            application.configureCommunicationsRoutes()

            assertNotNull(
                application.router.resolve(
                    HttpMethod.Post,
                    "/api/v1/webhooks/sendgrid",
                ),
            )
            assertNull(
                application.router.resolve(
                    HttpMethod.Get,
                    "/api/v1/webhooks/sendgrid",
                ),
            )
        } finally {
            application.shutdown()
            ProviderRegistry.clear()
        }
    }

    private fun sign(timestamp: String, body: String): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(timestamp.toByteArray(Charsets.UTF_8))
        signer.update(body.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private class RecordingDeliveryTracking : DeliveryTrackingService {
        val recorded = mutableListOf<DeliveryEvent>()
        val suppressions = mutableListOf<Triple<String, String, String?>>()
        var fail = false
        var failure: Throwable? = null

        override suspend fun recordEvent(event: DeliveryEvent) {
            failure?.let { throw it }
            if (fail) error("database unavailable")
            recorded.add(event)
        }

        override suspend fun getStatus(messageId: UUID, recipientId: UUID): DeliveryStatus? = null
        override suspend fun getStatusesForMessage(messageId: UUID): List<DeliveryStatus> = emptyList()
        override suspend fun getStatuses(offset: Long, limit: Int): List<DeliveryStatus> = emptyList()
        override suspend fun countStatuses(): Long = 0
        override suspend fun getHistoryForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryStatus> = emptyList()
        override suspend fun getEventsForRecipient(recipientId: UUID, offset: Long, limit: Int): List<DeliveryEvent> = emptyList()
        override suspend fun isSuppressed(email: String): Boolean = false
        override suspend fun suppress(email: String, reason: String, providerCode: String?) {
            suppressions += Triple(email, reason, providerCode)
        }
    }
}
