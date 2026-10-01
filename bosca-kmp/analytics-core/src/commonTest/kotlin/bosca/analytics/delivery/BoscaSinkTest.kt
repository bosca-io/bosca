package bosca.analytics.delivery

import bosca.analytics.api.AnalyticsContext
import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.DefaultAnalyticsEventFactory
import bosca.analytics.api.CurrentPageProvider
import bosca.analytics.api.Device
import bosca.analytics.api.ErrorInfo
import bosca.analytics.api.Geo
import bosca.analytics.api.Page
import bosca.analytics.persistence.InMemoryAnalyticsEventStore
import bosca.analytics.persistence.StoredAnalyticsEvent
import bosca.core.analytics.InstallationIdProvider
import bosca.core.security.model.Principal
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class BoscaSinkTest {
    @Test
    fun `flush sends collector context and event payload`() = runTest {
        var request: HttpRequestData? = null
        val client = HttpClient(MockEngine { current ->
            request = current
            respond("accepted", HttpStatusCode.Accepted)
        })
        val sink = createSink(config(), client, InstallationIdProvider { "installation-1" })
        sink.setUserId("user-1")
        sink.setGeo(Geo(city = "Austin", region = "TX", country = "US"))
        sink.add(
            DefaultAnalyticsEventFactory(CurrentPageProvider { null }).createEvent(
                AnalyticsEventInput(
                    AnalyticsEventType.INTERACTION,
                    AnalyticsElement("save", "button", extras = mapOf("mode" to "edit")),
                    page = Page(path = "/editor"),
                ),
            ),
        )

        sink.flush()

        val body = (request?.body as? TextContent)?.text ?: error("events request body missing")
        val payload = Json.parseToJsonElement(body).jsonObject
        val context = payload.getValue("context").jsonObject
        val event = payload.getValue("events").jsonArray.single().jsonObject
        assertEquals("installation-1", request.headers[BoscaRequestHeaders.INSTALLATION_ID])
        assertEquals("app", request.headers[BoscaRequestHeaders.APP_ID])
        assertEquals("1.0", request.headers[BoscaRequestHeaders.APP_VERSION])
        assertEquals(context.getValue("session_id").jsonPrimitive.content, request.headers[BoscaRequestHeaders.SESSION_ID])
        assertEquals(sink.sessionId(), request.headers[BoscaRequestHeaders.SESSION_ID])
        assertEquals("app", context.getValue("app_id").jsonPrimitive.content)
        assertEquals("installation-1", context.getValue("device").jsonObject.getValue("installation_id").jsonPrimitive.content)
        assertEquals("user-1", context.getValue("user_id").jsonPrimitive.content)
        assertEquals("US", context.getValue("geo").jsonObject.getValue("country").jsonPrimitive.content)
        assertEquals("interaction", event.getValue("type").jsonPrimitive.content)
        assertEquals("/editor", event.getValue("page").jsonObject.getValue("path").jsonPrimitive.content)
        assertEquals(1, sink.flushed)
        assertEquals(0, sink.pendingSize())
        sink.close()
    }

    @Test
    fun `events capture the current authenticated principal`() = runTest {
        val principal = MutableStateFlow<Principal?>(
            Principal(Uuid.parse("10000000-0000-0000-0000-000000000001"), verified = true, primaryProfileId = null),
        )
        val payloads = mutableListOf<String>()
        val installationHeaders = mutableListOf<String>()
        var installationIdReads = 0
        val sink = createSink(
            config = config(),
            client = HttpClient(MockEngine { request ->
                payloads += (request.body as TextContent).text
                installationHeaders += request.headers[BoscaRequestHeaders.INSTALLATION_ID]
                    ?: error("installation header missing")
                respond("accepted", HttpStatusCode.Accepted)
            }),
            provider = InstallationIdProvider {
                installationIdReads++
                "installation-1"
            },
            userIdProvider = AnalyticsUserIdProvider { principal.value?.id?.toString() },
        )

        sink.add(event("signed-in"))
        principal.value = null
        sink.add(event("signed-out"))
        sink.flush()

        val contexts = payloads.map { body ->
            Json.parseToJsonElement(body).jsonObject.getValue("context").jsonObject
        }
        assertEquals(1, installationIdReads)
        assertEquals(
            contexts.map { it.getValue("device").jsonObject.getValue("installation_id").jsonPrimitive.content },
            installationHeaders,
        )
        assertEquals("10000000-0000-0000-0000-000000000001", contexts[0].getValue("user_id").jsonPrimitive.content)
        assertEquals(JsonNull, contexts[1].getValue("user_id"))
        sink.close()
    }

    @Test
    fun `failed delivery remains queued and succeeds on a later flush`() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (calls == 1) respond("no", HttpStatusCode.ServiceUnavailable)
            else respond("ok", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val sink = createSink(config(), client, InstallationIdProvider { "installation-1" })
        sink.add(event("completion", AnalyticsEventType.COMPLETION))

        sink.flush()
        assertEquals(1, sink.pendingSize())
        assertEquals(1, sink.failures)

        sink.flush()
        assertEquals(0, sink.pendingSize())
        assertEquals(1, sink.flushed)
        assertTrue(calls >= 2)
        sink.close()
    }

    @Test
    fun `anonymous debug sink owns lifecycle identity and truncates error stacks`() = runBlocking {
        val messages = mutableListOf<String>()
        var body = ""
        val sink = createSink(
            config = BoscaSinkConfig(
                url = "https://analytics.test/",
                appId = "app",
                appVersion = "1",
                clientId = "client",
                debug = true,
                anonymous = true,
                heartbeat = false,
                sessionTracking = false,
                autoFlush = false,
            ),
            client = HttpClient(MockEngine { request ->
                body = (request.body as TextContent).text
                respond("ok", HttpStatusCode.OK)
            }),
            provider = AnonymousInstallationIdProvider(),
            logger = AnalyticsLogger { message, _ -> messages += message },
        )

        val installationId = sink.installationId()
        assertEquals(installationId, sink.installationId())
        sink.setUserId("ignored-for-anonymous")
        sink.pause()
        sink.resume()
        sink.add(event("failure", AnalyticsEventType.ERROR, ErrorInfo("failed", stackTrace = "x".repeat(9_000))))
        sink.flush()

        val payload = Json.parseToJsonElement(body).jsonObject
        val context = payload.getValue("context").jsonObject
        val events = payload.getValue("events").jsonArray
        assertEquals("null", context.getValue("user_id").toString())
        val error = events.single { it.jsonObject.getValue("error") != JsonNull }.jsonObject.getValue("error").jsonObject
        assertEquals(8_192, error.getValue("stack_trace").jsonPrimitive.content.length)
        assertTrue(messages.any { it.contains("event error") })
        sink.close()
    }

    @Test
    fun `session tracking starts sessions emits heartbeats and supports foreground lifecycle`() = runBlocking {
        val sink = createSink(
            config = BoscaSinkConfig(
                url = "https://analytics.test",
                appId = "app",
                appVersion = "1",
                clientId = "client",
                heartbeat = true,
                sessionTracking = true,
                heartbeatInterval = 20.milliseconds,
                sessionTimeout = 100.milliseconds,
                autoFlush = false,
            ),
            client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK) }),
            provider = InstallationIdProvider { "iid" },
        )

        eventually { sink.pendingSize() >= 2 }
        sink.pause()
        val pausedSize = sink.pendingSize()
        delay(50)
        assertEquals(pausedSize, sink.pendingSize())
        sink.resume()
        eventually { sink.pendingSize() > pausedSize }
        sink.close()
    }

    @Test
    fun `automatic flush drains structured offline events and delivers errors immediately`() = runBlocking {
        val context = AnalyticsContext(
            appId = "app",
            appVersion = "1",
            clientId = "client",
            device = Device("persisted", "maker", "model", "platform", "en", "system", "UTC", "desktop", "1"),
            geo = Geo("Austin", "TX", "US"),
            sessionId = "offline-session",
        )
        val store = InMemoryAnalyticsEventStore(
            listOf(StoredAnalyticsEvent("offline-context", context, event("offline"))),
        )
        val payloads = mutableListOf<String>()
        val installationHeaders = mutableListOf<String>()
        val sessionHeaders = mutableListOf<String>()
        val sink = createSink(
            config = BoscaSinkConfig(
                url = "https://analytics.test",
                appId = "app",
                appVersion = "1",
                clientId = "client",
                sessionTracking = false,
                heartbeat = false,
                flushDelay = 10.milliseconds,
                autoFlush = true,
            ),
            client = HttpClient(MockEngine { request ->
                payloads += (request.body as TextContent).text
                installationHeaders += request.headers[BoscaRequestHeaders.INSTALLATION_ID]
                    ?: error("installation header missing")
                sessionHeaders += request.headers[BoscaRequestHeaders.SESSION_ID] ?: error("session header missing")
                respond("ok", HttpStatusCode.OK)
            }),
            provider = InstallationIdProvider { "iid" },
            store = store,
        )

        eventually { sink.flushed == 1L }
        val offlineContext = Json.parseToJsonElement(payloads.first()).jsonObject.getValue("context").jsonObject
        assertEquals("app", offlineContext.getValue("app_id").jsonPrimitive.content)
        assertEquals("persisted", offlineContext.getValue("device").jsonObject.getValue("installation_id").jsonPrimitive.content)
        assertEquals("persisted", installationHeaders.first())
        assertEquals("offline-session", sessionHeaders.first())
        assertEquals("US", offlineContext.getValue("geo").jsonObject.getValue("country").jsonPrimitive.content)

        sink.add(event("error", AnalyticsEventType.ERROR, ErrorInfo("failed")))
        eventually { sink.flushed == 2L }
        assertEquals(sink.sessionId(), sessionHeaders.last())
        sink.close()
    }

    @Test
    fun `automatic flush retains failures and notices events added during delivery`() = runBlocking {
        var fail = true
        var addDuringDelivery = true
        lateinit var sink: BoscaSink
        val client = HttpClient(MockEngine {
            if (addDuringDelivery) {
                addDuringDelivery = false
                sink.add(event("concurrent"))
            }
            if (fail) respond("no", HttpStatusCode.ServiceUnavailable) else respond("ok", HttpStatusCode.OK)
        })
        sink = createSink(
            config = BoscaSinkConfig(
                url = "https://analytics.test",
                appId = "app",
                appVersion = "1",
                clientId = "client",
                heartbeat = false,
                sessionTracking = false,
                autoFlush = true,
                flushDelay = 100.milliseconds,
            ),
            client = client,
            provider = InstallationIdProvider { "iid" },
        )
        sink.add(event("first"))
        sink.flush()
        assertEquals(1, sink.failures)
        assertEquals(2, sink.pendingSize())

        fail = false
        sink.flush()
        eventually { sink.pendingSize() == 0 }
        assertEquals(2, sink.flushed)
        sink.close()
    }

    @Test
    fun `default sink configuration exposes documented defaults`() {
        val config = BoscaSinkConfig(
            url = "https://analytics.test",
            appId = "app",
            appVersion = "1",
            clientId = "client",
        )
        assertTrue(config.heartbeat)
        assertFalse(config.anonymous)
        assertEquals(100, config.flushBatchSize)
        assertEquals("app-client", config.storageNamespace)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            config.copy(flushBatchSize = 0)
        }
    }

    @Test
    fun `close still releases lifecycle state when context initialization fails`() = runTest {
        val sink = createSink(
            config(),
            HttpClient(MockEngine { respond("unused") }),
            InstallationIdProvider { error("identity failed") },
        )

        assertTrue(
            kotlin.test.assertFailsWith<IllegalStateException> { sink.close() }
                .message.orEmpty().contains("identity failed"),
        )
    }

    @Test
    fun `flush drains ten thousand stored events in bounded payloads`() = runTest {
        val context = AnalyticsContext(
            appId = "app",
            appVersion = "1",
            clientId = "client",
            device = Device("iid", "maker", "model", "desktop", "en", "Desktop", "UTC", "desktop", "1"),
            sessionId = "backlog",
        )
        val store = InMemoryAnalyticsEventStore(
            (0 until 10_000).map { index ->
                StoredAnalyticsEvent(
                    contextId = "context",
                    context = context,
                    event = event("event-$index").copy(created = index.toLong()),
                )
            },
        )
        val payloadSizes = mutableListOf<Int>()
        val sink = createSink(
            config = BoscaSinkConfig(
                url = "https://analytics.test",
                appId = "app",
                appVersion = "1",
                clientId = "client",
                heartbeat = false,
                sessionTracking = false,
                autoFlush = false,
                flushBatchSize = 128,
            ),
            client = HttpClient(MockEngine { request ->
                val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                payloadSizes += payload.getValue("events").jsonArray.size
                respond("ok", HttpStatusCode.OK)
            }),
            provider = InstallationIdProvider { "iid" },
            store = store,
        )

        sink.flush()

        assertEquals(10_000, payloadSizes.sum())
        assertTrue(payloadSizes.all { it in 1..128 })
        assertEquals(10_000, sink.flushed)
        assertEquals(0, sink.pendingSize())
        sink.close()
    }

    private suspend fun eventually(condition: suspend () -> Boolean) {
        withTimeout(2.seconds) {
            while (!condition()) delay(5)
        }
    }

    private fun event(
        id: String,
        type: AnalyticsEventType = AnalyticsEventType.INTERACTION,
        error: ErrorInfo? = null,
    ) = AnalyticsEvent(
        clientId = id,
        type = type,
        created = 1,
        createdMicros = 2,
        element = AnalyticsElement(id, "operation"),
        error = error,
    )

    private fun config() = BoscaSinkConfig(
        url = "https://analytics.test",
        appId = "app",
        appVersion = "1.0",
        clientId = "client",
        heartbeat = false,
        sessionTracking = false,
        autoFlush = false,
    )

    private fun createSink(
        config: BoscaSinkConfig,
        client: HttpClient,
        provider: InstallationIdProvider,
        store: InMemoryAnalyticsEventStore = InMemoryAnalyticsEventStore(),
        logger: AnalyticsLogger = AnalyticsLogger { _, _ -> },
        userIdProvider: AnalyticsUserIdProvider? = null,
    ) = BoscaSink(
        config = config,
        client = client,
        eventStore = store,
        installationProvider = provider,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        eventFactory = DefaultAnalyticsEventFactory(CurrentPageProvider { null }),
        logger = logger,
        userIdProvider = userIdProvider,
    )
}
