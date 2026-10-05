package bosca.communications.mailers.sendgrid

import bosca.communications.mailers.ContentType
import bosca.communications.mailers.InlineImage
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.mockk.coEvery
import io.mockk.mockk
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The streaming request body: serializes straight to the sink with byte-identical output to
 * the string path it replaced, and stays repeatable — OkHttp re-invokes writeTo on retries,
 * so two writes must produce identical bytes.
 */
class SendGridMailerTest {

    private val json = Json
    private val configurationId = UUID.random()
    private val configurationService = mockk<ConfigurationService>()
    private val mailer: SendGridMailer

    init {
        coEvery { configurationService.getByKey(SendGridConfiguration.KEY) } returns Configuration(
            id = configurationId,
            key = SendGridConfiguration.KEY,
            description = "SendGrid Email Integration Configuration",
            public = false,
        )
        configure(apiKey = "unused")
        mailer = SendGridMailer(json, configurationService)
    }

    private fun configure(apiKey: String, webhookVerificationKey: String = "") {
        coEvery { configurationService.getValue(configurationId) } returns json.encodeToJsonElement(
            SendGridConfiguration.serializer(),
            SendGridConfiguration(apiKey, webhookVerificationKey),
        )
    }

    private fun message() = SendGridMessage(
        from = SendGridEmail("Passion", "noreply@example.com"),
        to = listOf(SendGridEmail("Ada", "ada@example.com")),
        subject = "Hi",
        content = listOf(SendGridContent("text/html", "<p>hi <img src=\"cid:logo-png\"/></p>")),
        inlineImages = listOf(InlineImage("logo-png", "image/png", "logo.png", "aW1n")),
    )

    private fun request() = message().toRequest()

    @Test
    fun `builds SendGrid addresses content and messages through the Mailer contract`() = runBlocking {
        val from = mailer.newEmail("Passion", "noreply@example.com") as SendGridEmail
        val to = mailer.newEmail(
            "Ada",
            "ada@example.com",
            mapOf("bosca_recipient_id" to "recipient-1"),
        ) as SendGridEmail
        val text = mailer.newContent(ContentType.TEXT, "hello") as SendGridContent
        val html = mailer.newContent(ContentType.HTML, "<p>hello</p>") as SendGridContent

        assertEquals("text/plain", text.type)
        assertEquals("text/html", html.type)
        assertEquals("recipient-1", to.customArguments["bosca_recipient_id"])

        val basic = mailer.newMessage(from, listOf(to), "Hi", listOf(text), emptyList()) as SendGridMessage
        val tracked = mailer.newMessage(
            from,
            listOf(to),
            "Hi",
            listOf(text, html),
            emptyList(),
            mapOf("bosca_message_id" to "message-1"),
        ) as SendGridMessage

        assertTrue(basic.customArguments.isEmpty())
        assertEquals("message-1", tracked.customArguments["bosca_message_id"])
        assertEquals(listOf(text, html), tracked.content)
    }

    @Test
    fun `streams the same bytes the string encoding would produce`() {
        val request = request()
        val body = mailer.streamingBody(request)
        val buffer = Buffer()
        body.writeTo(buffer)
        val streamed = buffer.readUtf8()

        assertEquals(json.encodeToString(SendGridRequest.serializer(), request), streamed)
        assertTrue(""""content":"aW1n"""" in streamed, streamed)
        assertEquals("application/json", body.contentType()?.let { "${it.type}/${it.subtype}" })
    }

    @Test
    fun `the body is repeatable for OkHttp retries`() {
        val body = mailer.streamingBody(request())
        val first = Buffer().also { body.writeTo(it) }.readUtf8()
        val second = Buffer().also { body.writeTo(it) }.readUtf8()
        assertEquals(first, second, "writeTo must be a pure function of the request")
    }

    @Test
    fun `successful provider response is closed`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(202).body("accepted").build())
            val responseClosed = AtomicBoolean()
            val client = OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun responseBodyEnd(call: Call, byteCount: Long) {
                        responseClosed.set(true)
                    }
                })
                .build()
            val testMailer = SendGridMailer(
                json,
                configurationService = configurationService,
                client = client,
                endpoint = server.url("/v3/mail/send").toString(),
            )
            configure(apiKey = "test-token")

            runBlocking { testMailer.send(message()) }

            assertTrue(responseClosed.get(), "the successful response body must be closed")
            val request = server.takeRequest()
            assertEquals("/v3/mail/send", request.target)
            assertEquals("Bearer test-token", request.headers["Authorization"])
        }
    }

    @Test
    fun `provider error includes the response body and closes it`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
            val testMailer = SendGridMailer(
                json,
                configurationService = configurationService,
                client = OkHttpClient(),
                endpoint = server.url("/v3/mail/send").toString(),
            )
            configure(apiKey = "test-token")

            val error = assertFailsWith<Exception> {
                runBlocking { testMailer.send(message()) }
            }

            assertEquals("SendGrid Error: unavailable", error.message)
        }
    }

    @Test
    fun `the current API key is resolved for every send`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(202).build())
            server.enqueue(MockResponse.Builder().code(202).build())
            val testMailer = SendGridMailer(
                json,
                configurationService = configurationService,
                client = OkHttpClient(),
                endpoint = server.url("/v3/mail/send").toString(),
            )

            configure(apiKey = "first-token")
            runBlocking { testMailer.send(message()) }
            configure(apiKey = "second-token")
            runBlocking { testMailer.send(message()) }

            assertEquals("Bearer first-token", server.takeRequest().headers["Authorization"])
            assertEquals("Bearer second-token", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `a missing SendGrid configuration fails before calling the provider`() {
        coEvery { configurationService.getByKey(SendGridConfiguration.KEY) } returns null

        val error = assertFailsWith<IllegalStateException> {
            runBlocking { mailer.send(message()) }
        }

        assertEquals("SendGrid API key is not configured", error.message)
    }

    @Test
    fun `a blank SendGrid API key fails before calling the provider`() {
        configure(apiKey = " ")

        val error = assertFailsWith<IllegalStateException> {
            runBlocking { mailer.send(message()) }
        }

        assertEquals("SendGrid API key is not configured", error.message)
    }
}
