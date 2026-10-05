package bosca.collaboration.jobs

import bosca.chat.model.ChatChannel
import bosca.chat.model.ChatChannelType
import bosca.chat.model.ChatMessage
import bosca.chat.service.ChatService
import bosca.collaboration.bridge.BridgeAdapterRegistry
import bosca.collaboration.bridge.BridgeBinding
import bosca.collaboration.bridge.BridgePlatform
import bosca.collaboration.bridge.BridgeService
import bosca.collaboration.bridge.slack.SlackAdapter
import bosca.collaboration.bridge.slack.SlackApiException
import bosca.communications.model.MessageContent
import bosca.communications.model.MessageContentType
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration coverage for the outbound bridge dispatch path. Exercises
 * the real [SlackAdapter] (with HTTP behavior simulated by Ktor's
 * MockEngine), the real [BridgeAdapterRegistry], and a mocked
 * [BridgeService] / [ChatService] — i.e. every component the executor
 * touches except the JetStream message store and the actual Slack server.
 *
 * Goal: catch wiring bugs the per-class unit tests can't see — the
 * binding's bot token actually reaches Slack as a Bearer header, the
 * message body actually gets serialized as a JSON object (not a quoted
 * JSON string — we shipped that bug once), and the returned `ts`
 * actually flows back into [BridgeService.recordMessageMapping].
 */
class BridgeOutboundExecutorIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val channelId = UUID.random()
    private val senderId = UUID.random()
    private val channel = ChatChannel(
        id = channelId,
        name = "#general",
        type = ChatChannelType.GROUP,
    )

    private val message = ChatMessage(
        sequence = 42L,
        timestamp = OffsetDateTime.now(),
        senderId = senderId,
        content = listOf(MessageContent(MessageContentType.TEXT, "hello slack")),
    )

    private fun makeBinding(
        platform: BridgePlatform = BridgePlatform.SLACK,
        externalChannelId: String = "C09876543",
    ) = BridgeBinding(
        id = UUID.random(),
        channelId = channelId,
        platform = platform,
        externalChannelId = externalChannelId,
        workspaceId = "T01234567",
    )

    private fun makeJob() = BridgeOutboundJob(
        channelId = channelId,
        senderId = senderId,
        sequence = message.sequence,
        content = message.content,
        senderName = "Alice",
    )

    /** Captures every request the adapter makes so each test can assert against it. */
    private data class RecordedRequest(val url: String, val authHeader: String?, val body: String)
    private val requests = mutableListOf<RecordedRequest>()

    private fun makeRegistry(
        responseBody: String = """{"ok":true,"ts":"1700000000.000100"}""",
        token: String = "xoxb-fake",
    ): BridgeAdapterRegistry {
        requests.clear()
        val engine = MockEngine { request ->
            val body = when (val content = request.body) {
                is io.ktor.http.content.TextContent -> content.text
                is io.ktor.http.content.ByteArrayContent -> content.bytes().toString(Charsets.UTF_8)
                else -> ""
            }
            requests.add(
                RecordedRequest(
                    url = request.url.toString(),
                    authHeader = request.headers["Authorization"],
                    body = body,
                ),
            )
            respond(
                content = ByteReadChannel(responseBody),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine)
        val slackAdapter = SlackAdapter(httpClient, json, tokenProvider = { token })
        return BridgeAdapterRegistry(listOf(slackAdapter))
    }

    private lateinit var bridgeService: BridgeService
    private lateinit var chatService: ChatService

    @BeforeTest
    fun setup() {
        bridgeService = mockk(relaxed = true)
        chatService = mockk(relaxed = true)
        coEvery { chatService.getById(channelId) } returns channel
        coEvery { chatService.getMessages(channelId, after = message.sequence - 1, limit = 1) } returns listOf(message)
    }

    @Test
    fun `dispatch posts the message to Slack and records the returned ts`() = runBlocking {
        val binding = makeBinding()
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns listOf(binding)

        BridgeOutboundExecutor.dispatch(
            job = makeJob(),
            bridgeService = bridgeService,
            adapterRegistry = makeRegistry(),
            chatService = chatService,
        )

        assertEquals(1, requests.size)
        val req = requests.single()
        assertEquals("https://slack.com/api/chat.postMessage", req.url)
        assertEquals("Bearer xoxb-fake", req.authHeader)
        // Body is a JSON object, not a quoted JSON string. The earlier
        // adapter shipped with a double-encoding bug; this assertion is
        // the regression guard.
        val body = json.parseToJsonElement(req.body).jsonObject
        assertEquals("C09876543", body["channel"]?.jsonPrimitive?.content)
        assertEquals("hello slack", body["text"]?.jsonPrimitive?.content)
        assertEquals("Alice", body["username"]?.jsonPrimitive?.content)

        // The ts Slack returned was forwarded to the bridge service so
        // edit/delete propagation can find the right Slack message.
        coVerify(exactly = 1) {
            bridgeService.recordMessageMapping(
                channelId = channelId,
                sequence = message.sequence,
                platform = BridgePlatform.SLACK,
                externalMessageId = "1700000000.000100",
            )
        }
    }

    @Test
    fun `dispatch is a no-op when the channel has no bindings`() = runBlocking {
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns emptyList()
        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, makeRegistry(), chatService)
        assertTrue(requests.isEmpty())
        coVerify(exactly = 0) { bridgeService.recordMessageMapping(any(), any(), any(), any()) }
    }

    @Test
    fun `dispatch is a no-op when the message no longer exists`() = runBlocking {
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns listOf(makeBinding())
        coEvery { chatService.getMessages(channelId, after = message.sequence - 1, limit = 1) } returns emptyList()
        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, makeRegistry(), chatService)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `dispatch skips bindings with no registered adapter without failing the others`() = runBlocking {
        // First binding is for a platform we have no adapter for; second
        // is the real Slack one. The Slack delivery still happens.
        val unsupported = makeBinding(platform = BridgePlatform.TEAMS, externalChannelId = "19:teams")
        val supported = makeBinding(externalChannelId = "C09876543")
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns listOf(unsupported, supported)

        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, makeRegistry(), chatService)

        assertEquals(1, requests.size)
        coVerify(exactly = 1) {
            bridgeService.recordMessageMapping(channelId, message.sequence, BridgePlatform.SLACK, "1700000000.000100")
        }
    }

    @Test
    fun `dispatch swallows per-binding failures so other bindings still go out`() = runBlocking {
        // Both Slack bindings: first one's HTTP call returns ok=false (so
        // SlackAdapter throws SlackApiException), second succeeds. We
        // expect the second to land regardless and the first to be
        // logged + swallowed.
        val failingBinding = makeBinding(externalChannelId = "C111")
        val workingBinding = makeBinding(externalChannelId = "C222")
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns listOf(failingBinding, workingBinding)

        // A registry that returns a failing-then-succeeding adapter via
        // request inspection — first POST to C111 fails, second to C222
        // succeeds.
        var call = 0
        val engine = MockEngine { request ->
            call += 1
            val body = (request.body as io.ktor.http.content.TextContent).text
            requests.add(RecordedRequest(request.url.toString(), request.headers["Authorization"], body))
            val parsed = json.parseToJsonElement(body).jsonObject
            val slackChannel = parsed["channel"]?.jsonPrimitive?.content
            val response = if (slackChannel == "C111") {
                """{"ok":false,"error":"channel_not_found"}"""
            } else {
                """{"ok":true,"ts":"1700000000.000200"}"""
            }
            respond(
                content = ByteReadChannel(response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val adapter = SlackAdapter(client, json, tokenProvider = { "xoxb-fake" })
        val registry = BridgeAdapterRegistry(listOf(adapter))

        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, registry, chatService)

        // Both bindings were attempted.
        assertEquals(2, requests.size)
        // Only the successful one's mapping was recorded — total of 1
        // recordMessageMapping call, and it carries the successful ts.
        coVerify(exactly = 1) {
            bridgeService.recordMessageMapping(channelId, message.sequence, BridgePlatform.SLACK, any<String>())
        }
        coVerify(exactly = 1) {
            bridgeService.recordMessageMapping(channelId, message.sequence, BridgePlatform.SLACK, "1700000000.000200")
        }
    }

    @Test
    fun `dispatch propagates a freshly rotated bot token via tokenProvider on every call`() = runBlocking {
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns listOf(makeBinding())

        // tokenProvider is called once per sendMessage in production;
        // verify the registry asks for the token freshly each time so a
        // mid-flight rotation is picked up without a restart.
        var tokenCalls = 0
        val engine = MockEngine { request ->
            requests.add(RecordedRequest(request.url.toString(), request.headers["Authorization"], ""))
            respond(
                content = ByteReadChannel("""{"ok":true,"ts":"1.0"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine)
        val adapter = SlackAdapter(client, json, tokenProvider = {
            tokenCalls += 1
            "rotated-token-$tokenCalls"
        })
        val registry = BridgeAdapterRegistry(listOf(adapter))

        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, registry, chatService)
        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, registry, chatService)

        assertEquals(2, tokenCalls)
        assertEquals("Bearer rotated-token-1", requests[0].authHeader)
        assertEquals("Bearer rotated-token-2", requests[1].authHeader)
    }

    @Test
    fun `dispatch surfaces a SlackApiException from the adapter as a swallowed error and skips recordMessageMapping`() = runBlocking {
        // Single binding, response is ok=false. SlackAdapter throws,
        // dispatch logs+swallows, no mapping recorded.
        coEvery { bridgeService.getBindingsForChannel(channelId) } returns listOf(makeBinding())
        val registry = makeRegistry(responseBody = """{"ok":false,"error":"invalid_auth"}""")

        // Should NOT throw out of dispatch — the per-binding catch
        // contains it.
        BridgeOutboundExecutor.dispatch(makeJob(), bridgeService, registry, chatService)
        assertEquals(1, requests.size)
        coVerify(exactly = 0) {
            bridgeService.recordMessageMapping(any(), any(), BridgePlatform.SLACK, any())
        }

        // Sanity: the SlackApiException is the right shape if a future
        // refactor decides to propagate it.
        kotlin.runCatching {
            val adapter = SlackAdapter(HttpClient(MockEngine { _ ->
                respond(ByteReadChannel("""{"ok":false,"error":"invalid_auth"}"""),
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }), json, { "x" })
            adapter.sendMessage(makeBinding(), message, "Alice")
        }.exceptionOrNull().let { ex ->
            assertTrue(ex is SlackApiException, "expected SlackApiException, got $ex")
            assertEquals("invalid_auth", ex.errorCode)
        }
    }
}
