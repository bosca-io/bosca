package bosca.communications.service

import bosca.bml.message.client.BmlMessageRenderException
import bosca.bml.message.client.BmlMessageServerClient
import bosca.communications.configuration.BoscaMessageBranding
import bosca.communications.model.PushAttachment
import bosca.communications.model.PushAction
import bosca.communications.model.PushConversation
import bosca.communications.model.PushOptions
import bosca.communications.model.PushRichContent
import bosca.communications.model.ResolvedMessageTemplate
import bosca.configuration.model.Configuration
import bosca.configuration.service.ConfigurationService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import com.auth0.jwt.interfaces.DecodedJWT
import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the send path's template renderer maps a [ResolvedMessageTemplate] + recipient onto the
 * message server's render API (path, request fields incl. the registry's version pin and the
 * unsubscribe/preferences URLs, payload passthrough), maps the response into [RenderedEmail],
 * propagates server refusals as [BmlMessageRenderException] so the send path records a FAILED
 * delivery event instead of mailing something malformed, and assembles editor previews
 * (production resolution, version override, browser-viewable data: URI images).
 */
class BmlMessageTemplateRendererServiceImplTest {

    private lateinit var server: HttpServer
    private val lastPath = AtomicReference<String>()
    private val lastBody = AtomicReference<String>()
    private val lastAuthorization = AtomicReference<String>()
    private var respond: (String) -> Pair<Int, String> = { _ ->
        200 to """{"project":"acme","templateKey":"welcome","version":"7","email":{"subject":"Hi Ada","html":"<p>H</p>","text":"T",""" +
            """"images":[{"cid":"logo-png","source":"logo.png","mediaType":"image/png","filename":"logo.png"}]}}"""
    }

    private val assetHits = java.util.concurrent.atomic.AtomicInteger(0)

    @BeforeTest
    fun boot() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/projects") {
                val body = (
                    """[{"project":"acme","activeVersion":"7","templates":[{"key":"welcome","samplePayload":{"courseName":""},"supportsPush":true,""" +
                        """"payloadSchema":{"type":"object","properties":{"courseName":{"type":"string"}}}},{"key":"goodbye"}]}]"""
                    ).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }
            if (exchange.requestURI.path == "/projects/acme/versions") {
                val body = """["7","6"]""".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }
            if (exchange.requestURI.path.startsWith("/assets/")) {
                assetHits.incrementAndGet()
                val bytes = "img-bytes".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            lastPath.set(exchange.requestURI.path)
            lastBody.set(exchange.requestBody.readBytes().decodeToString())
            lastAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val (code, body) = respond(exchange.requestURI.path)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest
    fun shutdown() {
        server.stop(0)
    }

    private val registry = PreviewRegistry()
    private val securityService = mockk<SecurityService>()
    private val configurationService = mockk<ConfigurationService>()
    private val json = Json { ignoreUnknownKeys = true }
    private val serviceAccount = Principal(verified = true, anonymous = false)

    private fun renderer(): BmlMessageTemplateRendererServiceImpl {
        coEvery { securityService.getPrincipalByIdentifier("sa") } returns serviceAccount
        coEvery { securityService.getPrincipalGroups(serviceAccount.id) } returns emptyList()
        coEvery { securityService.createJwtToken(serviceAccount, emptyMap()) } returns mockk<DecodedJWT> {
            every { token } returns "service-jwt"
            every { expiresAtAsInstant } returns java.time.Instant.now().plusSeconds(3600)
        }
        return BmlMessageTemplateRendererServiceImpl(
            BmlMessageServerClient("http://localhost:${server.address.port}"),
            registry,
            BmlMessageServerTokenProvider(securityService),
            configurationService,
            json,
        )
    }

    @Test
    fun `maps the template reference and recipient onto the render API and back`() {
        val rendered = runBlocking {
            renderer().render(
                ResolvedMessageTemplate(project = "acme", templateKey = "welcome", version = "7"),
                payload = Json.parseToJsonElement("""{"courseName":"ET"}"""),
                messageId = "m-1",
                recipientId = "p-1",
                recipientName = "Ada",
                recipientEmail = "ada@example.com",
                unsubscribeUrl = "https://example.com/unsub?token=t1",
                preferencesUrl = "https://example.com/prefs?token=t1",
            )
        }
        assertEquals("Hi Ada", rendered.subject)
        assertEquals("<p>H</p>", rendered.html)
        assertEquals("T", rendered.text)
        assertEquals("7", rendered.version)
        val image = rendered.images.single()
        assertEquals("logo-png", image.cid)
        assertEquals("image/png", image.mediaType)
        assertEquals("logo.png", image.filename)
        assertEquals("img-bytes", java.util.Base64.getDecoder().decode(image.contentBase64).decodeToString())

        assertEquals("/render/acme/welcome", lastPath.get())
        assertEquals("Bearer service-jwt", lastAuthorization.get())
        val request = Json.parseToJsonElement(lastBody.get()).jsonObject
        assertEquals("EMAIL", request["channel"]?.jsonPrimitive?.content)
        assertEquals("m-1", request["messageId"]?.jsonPrimitive?.content)
        assertEquals("7", request["version"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/unsub?token=t1", request["unsubscribeUrl"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/prefs?token=t1", request["preferencesUrl"]?.jsonPrimitive?.content)
        assertEquals("p-1", request["recipientId"]?.jsonPrimitive?.content)
        assertEquals("Ada", request["recipientName"]?.jsonPrimitive?.content)
        assertEquals("ada@example.com", request["recipientEmail"]?.jsonPrimitive?.content)
        assertEquals("ET", request["payload"]?.jsonObject?.get("courseName")?.jsonPrimitive?.content)
    }

    @Test
    fun `bosca message branding is overlaid from configuration at render time`() {
        val renderer = renderer()
        val configurationId = UUID.random()
        coEvery { configurationService.getByKey(BoscaMessageBranding.KEY) } returns Configuration(
            id = configurationId,
            key = BoscaMessageBranding.KEY,
            description = "Bosca message branding",
            public = false,
        )
        coEvery { configurationService.getValue(configurationId) } returns json.encodeToJsonElement(
            BoscaMessageBranding(
                title = "Acme",
                logoUrl = "https://cdn.acme.example/email-logo.png",
                logoOnly = true,
                primaryColor = "#123456",
                accentColor = "#abc",
            ),
        )

        runBlocking {
            renderer.render(
                ResolvedMessageTemplate(project = "bosca-messages", templateKey = "welcome", version = "7"),
                payload = Json.parseToJsonElement(
                    """{"appName":"payload title","logoUrl":"payload-logo","logoOnly":false,"primaryColor":"#000000","accentColor":"#ffffff","courseName":"ET"}""",
                ),
            )
        }

        val payload = Json.parseToJsonElement(lastBody.get()).jsonObject.getValue("payload").jsonObject
        assertEquals("Acme", payload.getValue("appName").jsonPrimitive.content)
        assertEquals("https://cdn.acme.example/email-logo.png", payload.getValue("logoUrl").jsonPrimitive.content)
        assertEquals(true, payload.getValue("logoOnly").jsonPrimitive.content.toBoolean())
        assertEquals("#123456", payload.getValue("primaryColor").jsonPrimitive.content)
        assertEquals("#abc", payload.getValue("accentColor").jsonPrimitive.content)
        assertEquals("ET", payload.getValue("courseName").jsonPrimitive.content)
    }

    @Test
    fun `invalid configured colors fall back to email-safe defaults`() {
        val normalized = BoscaMessageBranding(
            title = "  ",
            logoUrl = "javascript:alert(1)",
            primaryColor = "red; display:none",
            accentColor = "not-a-color",
        ).normalized()

        assertEquals(BoscaMessageBranding.DEFAULT_TITLE, normalized.title)
        assertEquals("", normalized.logoUrl)
        assertEquals(BoscaMessageBranding.DEFAULT_PRIMARY_COLOR, normalized.primaryColor)
        assertEquals(BoscaMessageBranding.DEFAULT_ACCENT_COLOR, normalized.accentColor)
    }

    @Test
    fun `a server refusal propagates as a typed exception carrying the reason`() {
        respond = { _ -> 503 to """{"error":"message project 'acme' has no active version"}""" }
        val failure = assertFailsWith<BmlMessageRenderException> {
            runBlocking { renderer().render(ResolvedMessageTemplate("acme", "welcome")) }
        }
        assertEquals(503, failure.status)
        assertTrue("no active version" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an omitted payload renders with a null payload not a crash`() {
        val rendered = runBlocking { renderer().render(ResolvedMessageTemplate("acme", "welcome")) }
        assertEquals("Hi Ada", rendered.subject)
        assertEquals("null", Json.parseToJsonElement(lastBody.get()).jsonObject["payload"].toString())
        // No pin resolved -> the server renders its active version.
        assertEquals("null", Json.parseToJsonElement(lastBody.get()).jsonObject["version"].toString())
    }

    @Test
    fun `companion push maps rich attachments and the current conversation message`() {
        respond = { _ ->
            200 to """{"project":"acme","templateKey":"chat-message","version":"7","email":{"subject":"Email","html":"<p>Email</p>","text":"Email"},"push":{"title":"Ada in Planning","body":"Project update","options":{"defaultAction":{"id":"open-chat","label":"Abrir chat","url":"https://example.com/chat/1"},"richContent":{"attachments":[{"id":"image-1","url":"https://cdn.example/image.jpg","type":"IMAGE"}],"conversation":{"id":"channel-1","title":"Planning","messageId":"42","senderId":"ada","senderName":"Ada","body":"Project update","sentAtEpochMilliseconds":1234,"groupConversation":true}}}}}"""
        }

        val options = PushOptions(
            defaultAction = PushAction(id = "open-chat", url = "https://example.com/chat/1"),
            threadId = "chat-1",
            richContent = PushRichContent(
                attachments = listOf(PushAttachment(id = "image-1", url = "https://cdn.example/image.jpg")),
                conversation = PushConversation(
                    id = "channel-1",
                    title = "Planning",
                    messageId = "42",
                    senderId = "ada",
                    senderName = "Ada",
                    body = "Project update",
                    sentAtEpochMilliseconds = 1234,
                    groupConversation = true,
                ),
            ),
        )
        val rendered = runBlocking {
            renderer().renderPush(
                ResolvedMessageTemplate("acme", "chat-message", "7"),
                messageId = "message-42",
                recipientId = "profile-7",
                recipientName = "Ada Lovelace",
                recipientEmail = "ada@example.com",
                pushOptions = options,
                locale = Locale.forLanguageTag("es"),
            )
        }

        assertEquals("Ada in Planning", rendered?.title)
        assertEquals("Abrir chat", rendered?.options?.defaultAction?.label)
        assertEquals("https://example.com/chat/1", rendered?.options?.defaultAction?.url)
        assertEquals("https://cdn.example/image.jpg", rendered?.options?.richContent?.attachments?.single()?.url)
        assertEquals("42", rendered?.options?.richContent?.conversation?.messageId)
        val requestOptions = Json.parseToJsonElement(lastBody.get()).jsonObject["pushOptions"]?.jsonObject
        val request = Json.parseToJsonElement(lastBody.get()).jsonObject
        assertEquals("PUSH", request["channel"]?.jsonPrimitive?.content)
        assertEquals("message-42", request["messageId"]?.jsonPrimitive?.content)
        assertEquals("profile-7", request["recipientId"]?.jsonPrimitive?.content)
        assertEquals("Ada Lovelace", request["recipientName"]?.jsonPrimitive?.content)
        assertEquals("ada@example.com", request["recipientEmail"]?.jsonPrimitive?.content)
        assertEquals("\"es\"", request["locale"].toString())
        assertEquals("\"chat-1\"", requestOptions?.get("threadId").toString())
        assertEquals(
            "\"42\"",
            requestOptions?.get("richContent")?.jsonObject
                ?.get("conversation")?.jsonObject
                ?.get("messageId").toString(),
        )
    }

    @Test
    fun `preview resolves through the registry pin, renders, and swaps cid for data uris`() {
        registry.pin = "5"
        respond = { _ ->
            200 to """{"project":"acme","templateKey":"welcome","version":"5","email":{"subject":"Hi","html":"<p>x</p><img src=\"cid:logo-png\"/>","text":"x",""" +
                """"images":[{"cid":"logo-png","source":"logo.png","mediaType":"image/png","filename":"logo.png"}]}}"""
        }
        val preview = runBlocking {
            renderer().preview(bosca.communications.model.MessageBmlTemplate("acme", "welcome"), recipientName = "Ada")
        }
        assertEquals("acme", preview.project)
        assertEquals("welcome", preview.templateKey)
        assertEquals("5", preview.version)
        assertEquals("Hi", preview.subject)
        assertEquals("x", preview.text)
        assertTrue("data:image/png;base64," in preview.html, preview.html)
        assertTrue("cid:" !in preview.html, preview.html)
        // The pin rode the wire; an explicit override replaces it.
        assertEquals("\"5\"", Json.parseToJsonElement(lastBody.get()).jsonObject["version"].toString())
        runBlocking { renderer().preview(bosca.communications.model.MessageBmlTemplate("acme", "welcome"), version = "9") }
        assertEquals("\"9\"", Json.parseToJsonElement(lastBody.get()).jsonObject["version"].toString())
    }

    @Test
    fun `hosted projects merge the registry pin onto what the server hosts`() {
        registry.registered = mapOf("acme" to "6")
        val hosted = runBlocking { renderer().hostedProjects() }.single()
        assertEquals("acme", hosted.project)
        assertEquals("7", hosted.activeVersion)
        assertEquals("6", hosted.pinnedVersion, "the registry pin rides along for the dropdowns")
        assertEquals(listOf("welcome", "goodbye"), hosted.templates.map { it.key })
        assertTrue(hosted.templates[0].supportsPush)
        assertEquals("""{"courseName":""}""", hosted.templates[0].samplePayload.toString())
        assertEquals(
            """{"type":"object","properties":{"courseName":{"type":"string"}}}""",
            hosted.templates[0].payloadSchema.toString(),
            "the payload schema rides along for the payload-contract display",
        )
        assertEquals(null, hosted.templates[1].samplePayload)
        assertEquals(null, hosted.templates[1].payloadSchema)

        registry.registered = emptyMap()
        assertEquals(null, runBlocking { renderer().hostedProjects() }.single().pinnedVersion)
        assertEquals(listOf("7", "6"), runBlocking { renderer().versions("acme") })
    }

    /** Registry stub: resolve passes the reference through with an optional pin. */
    private class PreviewRegistry : BmlMessageRegistryService {
        var pin: String? = null
        var registered: Map<String, String?> = emptyMap()

        override suspend fun resolve(template: bosca.communications.model.MessageBmlTemplate) =
            bosca.communications.model.ResolvedMessageTemplate(template.project, template.templateKey, pin)

        override suspend fun listProjects() =
            registered.map { (key, pinned) -> bosca.communications.model.BmlMessageProject(key, pinnedVersion = pinned) }
        override suspend fun getProject(projectKey: String): bosca.communications.model.BmlMessageProject? = null
        override suspend fun registerProject(projectKey: String, description: String?, repositoryId: bosca.serialization.UUID?) =
            bosca.communications.model.BmlMessageProject(projectKey)

        override suspend fun pinProjectVersion(projectKey: String, version: String?) =
            bosca.communications.model.BmlMessageProject(projectKey)

        override suspend fun removeProject(projectKey: String) = false
    }
}
