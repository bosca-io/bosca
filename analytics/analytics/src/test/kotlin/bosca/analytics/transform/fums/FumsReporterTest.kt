package bosca.analytics.transform.fums

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FumsReporterTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `reports one encoded token and usage identifiers without an API key`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())
        FumsReporter(OkHttpClient(), server.url("/f3")).report("opaque+token&value", "device/id", "session id", "hashed-user")
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/f3", request.url.encodedPath)
        assertEquals(setOf("t", "dId", "sId", "uId"), request.url.queryParameterNames)
        assertEquals(listOf("opaque+token&value"), request.url.queryParameterValues("t"))
        assertEquals("device/id", request.url.queryParameter("dId"))
        assertEquals("session id", request.url.queryParameter("sId"))
        assertEquals("hashed-user", request.url.queryParameter("uId"))
        assertNull(request.headers["api-key"])
    }

    @Test
    fun `anonymous reports omit the optional user identifier`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        FumsReporter(OkHttpClient(), server.url("/f3")).report("token", "device", "session", null)
        assertNull(server.takeRequest().url.queryParameter("uId"))
    }

    @Test
    fun `HTTP failures are retryable without exposing the response body or token`() = runTest {
        server.enqueue(MockResponse.Builder().code(503).body("private-token private response").build())
        val error = assertFailsWith<IOException> {
            FumsReporter(OkHttpClient(), server.url("/f3")).report("private-token", "device", "session", null)
        }
        assertTrue(error.message.orEmpty().contains("503"))
        assertFalse(error.stackTraceToString().contains("private-token"))
        assertFalse(error.stackTraceToString().contains("private response"))
    }

    @Test
    fun `transport failures redact the private request URL`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("https://fums.test/?t=private-token") }.build()
        val error = assertFailsWith<IOException> {
            FumsReporter(client, server.url("/f3")).report("private-token", "device", "session", null)
        }
        assertFalse(error.stackTraceToString().contains("private-token"))
    }

    @Test
    fun `redirects do not forward the private token`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/redirected")).build())
        assertFailsWith<IOException> {
            FumsReporter(OkHttpClient(), server.url("/f3")).report("token", "device", "session", null)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling a pending report preserves cancellation`() = runTest {
        server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).build())
        val pending = async { FumsReporter(OkHttpClient(), server.url("/f3")).report("token", "device", "session", null) }
        runCurrent()
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
        pending.cancel()
        assertFailsWith<CancellationException> { pending.await() }
    }
}
