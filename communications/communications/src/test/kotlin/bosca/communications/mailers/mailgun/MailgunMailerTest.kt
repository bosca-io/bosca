package bosca.communications.mailers.mailgun

import bosca.communications.mailers.ContentType
import bosca.communications.mailers.InlineImage
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MailgunMailerTest {

    private val json = Json
    private val configurationId = UUID.random()
    private val configurationService = mockk<ConfigurationService>()

    init {
        coEvery { configurationService.getByKey(MailgunConfiguration.KEY) } returns Configuration(
            id = configurationId,
            key = MailgunConfiguration.KEY,
            description = "Mailgun Email Integration Configuration",
            public = false,
        )
        configure()
    }

    @Test
    fun `builds Mailgun values through the Mailer contract`() = runBlocking {
        val mailer = MailgunMailer(json, configurationService)
        val from = mailer.newEmail("Bosca", "noreply@example.com") as MailgunEmail
        val to = mailer.newEmail(
            "Ada",
            "ada@example.com",
            mapOf("bosca_recipient_id" to "recipient-1"),
        ) as MailgunEmail
        val text = mailer.newContent(ContentType.TEXT, "Hello") as MailgunContent
        val html = mailer.newContent(ContentType.HTML, "<p>Hello</p>") as MailgunContent
        val message = mailer.newMessage(
            from,
            listOf(to),
            "Hello",
            listOf(text, html),
            listOf(InlineImage("logo", "image/png", "logo.png", "aW1n")),
            mapOf("bosca_message_id" to "message-1"),
        ) as MailgunMessage

        assertEquals(1000, mailer.maxRecipientsPerMessage)
        assertEquals(ContentType.TEXT, text.type)
        assertEquals("recipient-1", to.customArguments["bosca_recipient_id"])
        assertEquals("message-1", message.customArguments["bosca_message_id"])
        assertEquals(listOf(text, html), message.content)
    }

    @Test
    fun `sends to the configured domain with basic authentication`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).body("{\"id\":\"queued\"}").build())
            configure(apiKey = "test-token", domain = "mg.example.com")
            val mailer = MailgunMailer(
                json,
                configurationService,
                OkHttpClient(),
                server.url("/"),
            )

            runBlocking { mailer.send(message()) }

            val request = server.takeRequest()
            assertEquals("/v3/mg.example.com/messages", request.target)
            assertEquals("Basic YXBpOnRlc3QtdG9rZW4=", request.headers["Authorization"])
            assertEquals("application/json", request.headers["Accept"])
            assertTrue(request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data; boundary="))
            assertTrue("name=\"subject\"" in request.body?.utf8().orEmpty())
        }
    }

    @Test
    fun `resolves current credentials and domain for every send`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(200).build())
            server.enqueue(MockResponse.Builder().code(200).build())
            val mailer = MailgunMailer(json, configurationService, OkHttpClient(), server.url("/"))

            configure(apiKey = "first-token", domain = "first.example.com")
            runBlocking { mailer.send(message()) }
            configure(apiKey = "second-token", domain = "second.example.com")
            runBlocking { mailer.send(message()) }

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/v3/first.example.com/messages", first.target)
            assertEquals("Basic YXBpOmZpcnN0LXRva2Vu", first.headers["Authorization"])
            assertEquals("/v3/second.example.com/messages", second.target)
            assertEquals("Basic YXBpOnNlY29uZC10b2tlbg==", second.headers["Authorization"])
        }
    }

    @Test
    fun `provider error includes the response body`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
            val mailer = MailgunMailer(json, configurationService, OkHttpClient(), server.url("/"))

            val error = assertFailsWith<Exception> { runBlocking { mailer.send(message()) } }

            assertEquals("Mailgun Error: unavailable", error.message)
        }
    }

    @Test
    fun `missing provider configuration fails before sending`() {
        val mailer = MailgunMailer(json, configurationService)
        coEvery { configurationService.getByKey(MailgunConfiguration.KEY) } returns null

        assertEquals(
            "Mailgun configuration is not configured",
            assertFailsWith<IllegalStateException> { runBlocking { mailer.send(message()) } }.message,
        )
    }

    @Test
    fun `blank credentials domain and base URL fail before sending`() {
        val mailer = MailgunMailer(json, configurationService)

        configure(apiKey = " ")
        assertEquals(
            "Mailgun API key is not configured",
            assertFailsWith<IllegalStateException> { runBlocking { mailer.send(message()) } }.message,
        )
        configure(domain = " ")
        assertEquals(
            "Mailgun domain is not configured",
            assertFailsWith<IllegalStateException> { runBlocking { mailer.send(message()) } }.message,
        )
        configure(apiBaseUrl = "not a URL")
        assertEquals(
            "Mailgun API base URL is not configured or invalid",
            assertFailsWith<IllegalStateException> { runBlocking { mailer.send(message()) } }.message,
        )
    }

    private fun configure(
        apiKey: String = "test-token",
        domain: String = "mg.example.com",
        apiBaseUrl: String = MailgunConfiguration.DEFAULT_API_BASE_URL,
    ) {
        coEvery { configurationService.getValue(configurationId) } returns json.encodeToJsonElement(
            MailgunConfiguration.serializer(),
            MailgunConfiguration(apiKey, domain, apiBaseUrl),
        )
    }

    private fun message() = MailgunMessage(
        from = MailgunEmail("Bosca", "noreply@example.com"),
        to = listOf(MailgunEmail("Ada", "ada@example.com")),
        subject = "Hello",
        content = listOf(MailgunContent(ContentType.TEXT, "Hello")),
    )
}
