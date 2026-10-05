package bosca.analytics.server

import bosca.analytics.model.Device
import bosca.analytics.model.Event
import bosca.analytics.model.EventType
import bosca.analytics.model.Events
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpServerAnalyticsClientTest {

    private val clients = mutableListOf<HttpServerAnalyticsClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun client(
        responses: () -> Response,
        maxBatchSize: Int = HttpServerAnalyticsClient.DEFAULT_BATCH_SIZE,
        maxRetries: Int = HttpServerAnalyticsClient.DEFAULT_MAX_RETRIES,
        intercepted: AtomicInteger = AtomicInteger(),
        capturedRequest: AtomicReference<Request> = AtomicReference(),
    ): HttpServerAnalyticsClient {
        val interceptor = Interceptor { chain ->
            intercepted.incrementAndGet()
            capturedRequest.set(chain.request())
            responses()
        }
        val ok = OkHttpClient.Builder().addInterceptor(interceptor).build()
        // The flusher loop calls sleeper(flushIntervalMs) before each
        // flush. We park it via delay(Long.MAX_VALUE) so the flusher
        // never drains the buffer non-deterministically. Short sleeps
        // (retry backoff) are skipped to keep tests fast.
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid",
            httpClient = ok,
            maxBatchSize = maxBatchSize,
            maxRetries = maxRetries,
            flushIntervalMs = Long.MAX_VALUE,
            sleeper = { ms -> if (ms == Long.MAX_VALUE) delay(ms) },
        )
        clients += client
        return client
    }

    private fun successResponse(): Response =
        Response.Builder()
            .request(Request.Builder().url("http://example.invalid/api/v1/events").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()

    private fun failureResponse(code: Int): Response =
        Response.Builder()
            .request(Request.Builder().url("http://example.invalid/api/v1/events").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("error")
            .body("".toResponseBody("text/plain".toMediaType()))
            .build()

    @Test
    fun `capture buffers events until the batch size is reached`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 3,
            intercepted = intercepted,
        )
        repeat(2) { client.capture(Event(created = it.toLong(), type = EventType.Session)) }
        assertEquals(0, intercepted.get(), "should not flush before batch size is reached")
        client.capture(Event(created = 3L, type = EventType.Session))
        assertEquals(1, intercepted.get(), "third event should trigger a flush")
    }

    @Test
    fun `capture pre-built batch sends immediately`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(responses = { successResponse() }, intercepted = intercepted)
        client.capture(Events(events = listOf(Event(created = 1L, type = EventType.Session)), sent = 0L, sentMicros = 0L))
        assertEquals(1, intercepted.get())
    }

    @Test
    fun `capture for subject sends its identity context immediately`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val ok = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            capturedRequest.set(chain.request())
            successResponse()
        }).build()
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid",
            appId = "server-app",
            httpClient = ok,
            flushIntervalMs = Long.MAX_VALUE,
            sleeper = { delay(Long.MAX_VALUE) },
        )
        clients += client
        val device = Device(
            installationId = "installation-1",
            manufacturer = "",
            model = "",
            platform = "web",
            primaryLocale = "en-US",
            systemName = "web",
            timezone = "UTC",
            type = "web",
            version = "1",
        )

        client.captureForSubject(
            Event(created = 1L, type = EventType.Assignment),
            userId = "principal-1",
            installationId = "installation-1",
            device = device,
        )

        val body = okio.Buffer().also { capturedRequest.get().body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"type\":\"assignment\""))
        assertTrue(body.contains("principal-1"))
        assertTrue(body.contains("installation-1"))
        val context = requireNotNull(Json.decodeFromString(Events.serializer(), body).context)
        assertEquals(context.appId, capturedRequest.get().header("X-App-ID"))
        assertEquals(context.appVersion, capturedRequest.get().header("X-App-Version"))
        assertEquals(context.device.installationId, capturedRequest.get().header("X-Installation-ID"))
        assertEquals(context.sessionId, capturedRequest.get().header("X-BA-Session-ID"))
    }

    @Test
    fun `capture for subject without configured app id is counted as dropped`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(responses = { successResponse() }, intercepted = intercepted)

        client.captureForSubject(
            Event(created = 1L, type = EventType.Assignment),
            userId = null,
            installationId = "installation-1",
            device = null,
        )

        assertEquals(0, intercepted.get())
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `flush drains the buffer and sends a single batch`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 100,
            intercepted = intercepted,
        )
        repeat(5) { client.capture(Event(created = it.toLong(), type = EventType.Session)) }
        client.flush()
        assertEquals(1, intercepted.get())
    }

    @Test
    fun `permanent client errors drop the batch without retrying`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { failureResponse(400) },
            maxBatchSize = 1,
            intercepted = intercepted,
            maxRetries = 5,
        )
        client.capture(Event(created = 1L, type = EventType.Session))
        assertEquals(1, intercepted.get(), "4xx (other than 408/429) should not retry")
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `429 retries up to maxRetries before dropping`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { failureResponse(429) },
            maxBatchSize = 1,
            intercepted = intercepted,
            maxRetries = 2,
        )
        client.capture(Event(created = 1L, type = EventType.Session))
        // Initial attempt + 2 retries = 3 calls, then drop.
        assertEquals(3, intercepted.get())
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `5xx retries up to maxRetries before dropping`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { failureResponse(503) },
            maxBatchSize = 1,
            intercepted = intercepted,
            maxRetries = 1,
        )
        client.capture(Event(created = 1L, type = EventType.Session))
        // Initial attempt + 1 retry = 2 calls, then drop.
        assertEquals(2, intercepted.get())
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `success after a transient failure stops the retry loop`() = runTest {
        val intercepted = AtomicInteger()
        val responses = listOf(failureResponse(503), successResponse())
        val it = responses.iterator()
        val client = client(
            responses = { it.next() },
            maxBatchSize = 1,
            intercepted = intercepted,
            maxRetries = 3,
        )
        client.capture(Event(created = 1L, type = EventType.Session))
        assertEquals(2, intercepted.get())
        assertEquals(0L, client.droppedEvents)
    }

    @Test
    fun `request body uses the events JSON envelope`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 1,
            capturedRequest = capturedRequest,
        )
        client.capture(Event(created = 1L, type = EventType.Session))
        val request = capturedRequest.get()
        assertEquals("http://example.invalid/api/v1/events", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
    }

    @Test
    fun `api key is added as a bearer authorization header when configured`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val ok = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            capturedRequest.set(chain.request())
            successResponse()
        }).build()
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid",
            apiKey = "secret",
            httpClient = ok,
            maxBatchSize = 1,
            flushIntervalMs = Long.MAX_VALUE,
            sleeper = { ms -> if (ms == Long.MAX_VALUE) delay(ms) },
        )
        clients += client
        client.capture(Event(created = 1L, type = EventType.Session))
        assertEquals("Bearer secret", capturedRequest.get().header("Authorization"))
    }

    @Test
    fun `captureException builds an error event and ships it`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 1,
            capturedRequest = capturedRequest,
        )
        client.captureException(IllegalStateException("kaboom"), fatal = true)
        // Body should serialize an Events envelope with one Error event.
        val body = okio.Buffer().also { capturedRequest.get().body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("kaboom"), "request body should serialize the exception message")
        assertTrue(body.contains("\"type\":\"error\""), "request body should contain the serialized event type")
    }

    @Test
    fun `captureException includes ambient session and respects explicit overrides`() = runTest {
        val request = AtomicReference<Request>()
        val client = client(responses = { successResponse() }, maxBatchSize = 1, capturedRequest = request)
        fun errorContext(): String {
            val body = okio.Buffer().also { request.get().body?.writeTo(it) }.readUtf8()
            return requireNotNull(Json.decodeFromString(Events.serializer(), body).events.single().error?.contextJson)
        }
        withAnalyticsContext(AnalyticsContext(sessionId = "ambient-session")) {
            client.captureException(RuntimeException("ambient"))
            assertTrue(errorContext().contains("ambient-session"))
            client.captureException(RuntimeException("explicit"), context = mapOf("session_id" to "explicit-session"))
            assertTrue(errorContext().contains("explicit-session"))
            kotlin.test.assertFalse(errorContext().contains("ambient-session"))
        }
    }

    @Test
    fun `captureException merges principal identifiers into the contextJson`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 1,
            capturedRequest = capturedRequest,
        )
        client.captureException(
            RuntimeException("oops"),
            fatal = false,
            appId = "app-1",
            sessionId = "sess-2",
            userId = "user-3",
            context = mapOf("tenant" to "acme"),
        )
        val body = okio.Buffer().also { capturedRequest.get().body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("context_json"), "error info must carry a context_json field")
        assertTrue(body.contains("app-1"), "app id should land in contextJson")
        assertTrue(body.contains("sess-2"), "session id should land in contextJson")
        assertTrue(body.contains("user-3"), "user id should land in contextJson")
        assertTrue(body.contains("acme"), "explicit context entries should land in contextJson")
    }

    @Test
    fun `capture after close is rejected and counted as dropped`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 100,
            intercepted = intercepted,
        )
        client.capture(Event(created = 1L, type = EventType.Session))
        client.close()
        clients.remove(client) // already closed
        client.capture(Event(created = 2L, type = EventType.Session))
        client.captureForSubject(
            Event(created = 3L, type = EventType.Assignment),
            userId = null,
            installationId = "installation-1",
            device = null,
        )
        assertEquals(2L, client.droppedEvents, "events captured after close should be dropped")
    }

    @Test
    fun `buffer overflow drops the oldest event and increments the drop counter`() = runTest {
        val intercepted = AtomicInteger()
        val ok = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            intercepted.incrementAndGet()
            successResponse()
        }).build()
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid",
            httpClient = ok,
            maxBatchSize = 100,             // high so we buffer
            maxBufferedEvents = 3,          // tiny cap
            flushIntervalMs = Long.MAX_VALUE,
            sleeper = { ms -> if (ms == Long.MAX_VALUE) delay(ms) },
        )
        clients += client
        repeat(5) { client.capture(Event(created = it.toLong(), type = EventType.Session)) }
        // 5 captured, cap of 3 ⇒ 2 drops expected.
        assertEquals(2L, client.droppedEvents)
    }

    @Test
    fun `constructor rejects invalid buffering and retry settings`() {
        assertFailsWith<IllegalArgumentException> {
            HttpServerAnalyticsClient("http://example.invalid", maxBatchSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpServerAnalyticsClient("http://example.invalid", flushIntervalMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpServerAnalyticsClient("http://example.invalid", maxRetries = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            HttpServerAnalyticsClient("http://example.invalid", maxBufferedEvents = 0)
        }
    }

    @Test
    fun `captureException handles each optional identity independently`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { successResponse() },
            maxBatchSize = 1,
            intercepted = intercepted,
        )

        client.captureException(RuntimeException("session"), false, null, "session-1", null, emptyMap())
        client.captureException(RuntimeException("user"), false, null, null, "user-1", emptyMap())
        client.captureException(RuntimeException("app"), false, "app-1", null, null, emptyMap())

        assertEquals(3, intercepted.get())
    }

    @Test
    fun `interface defaults build a context-free exception event`() = runTest {
        val intercepted = AtomicInteger()
        val api: ServerAnalyticsClient = client(
            responses = { successResponse() },
            maxBatchSize = 1,
            intercepted = intercepted,
        )

        api.captureException(RuntimeException("default arguments"))

        assertEquals(1, intercepted.get())
    }

    @Test
    fun `configured app id is attached to buffered batch context`() = runTest {
        val capturedRequest = AtomicReference<Request>()
        val ok = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            capturedRequest.set(chain.request())
            successResponse()
        }).build()
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid/",
            appId = "server-app",
            httpClient = ok,
            maxBatchSize = 1,
            flushIntervalMs = Long.MAX_VALUE,
            sleeper = { delay(Long.MAX_VALUE) },
        )
        clients += client

        client.capture(Event(created = 1L, type = EventType.Session))

        val body = okio.Buffer().also { capturedRequest.get().body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("server-app"))
    }

    @Test
    fun `serialization failures drop the batch without making a request`() = runTest {
        val json = mockk<Json>()
        every { json.encodeToString(Events.serializer(), any()) } throws IllegalStateException("broken serializer")
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid",
            maxBatchSize = 1,
            flushIntervalMs = Long.MAX_VALUE,
            json = json,
            sleeper = { delay(Long.MAX_VALUE) },
        )
        clients += client

        client.capture(Event(created = 1L, type = EventType.Session))

        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `408 is retried as a transient response`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { failureResponse(408) },
            maxBatchSize = 1,
            maxRetries = 1,
            intercepted = intercepted,
        )

        client.capture(Event(created = 1L, type = EventType.Session))

        assertEquals(2, intercepted.get())
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `redirect response is retried as a transient response`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { failureResponse(302) },
            maxBatchSize = 1,
            maxRetries = 1,
            intercepted = intercepted,
        )

        client.capture(Event(created = 1L, type = EventType.Session))

        assertEquals(2, intercepted.get())
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `network failure callback participates in retry accounting`() = runTest {
        val intercepted = AtomicInteger()
        val client = client(
            responses = { throw IOException("offline") },
            maxBatchSize = 1,
            maxRetries = 0,
            intercepted = intercepted,
        )

        client.capture(Event(created = 1L, type = EventType.Session))

        assertEquals(1, intercepted.get())
        assertEquals(1L, client.droppedEvents)
    }

    @Test
    fun `scheduled flusher sends buffered events and survives a sleeper failure`() = runBlocking {
        val startFlush = CompletableDeferred<Unit>()
        val requestSent = CompletableDeferred<Unit>()
        val loopContinued = CompletableDeferred<Unit>()
        val sleeperCalls = AtomicInteger()
        val ok = OkHttpClient.Builder().addInterceptor(Interceptor {
            requestSent.complete(Unit)
            successResponse()
        }).build()
        val client = HttpServerAnalyticsClient(
            baseUrl = "http://example.invalid",
            httpClient = ok,
            maxBatchSize = 100,
            flushIntervalMs = 1,
            sleeper = {
                when (sleeperCalls.incrementAndGet()) {
                    1 -> startFlush.await()
                    2 -> throw IllegalStateException("timer failed")
                    else -> {
                        loopContinued.complete(Unit)
                        delay(Long.MAX_VALUE)
                    }
                }
            },
        )
        clients += client

        client.capture(Event(created = 1L, type = EventType.Session))
        startFlush.complete(Unit)

        withTimeout(5_000) { requestSent.await() }
        withTimeout(5_000) { loopContinued.await() }
        assertTrue(sleeperCalls.get() >= 3)
    }
}
