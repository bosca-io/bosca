package bosca.hubspot.client

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.time.OffsetDateTime as JOffsetDateTime

class SendEventTest {

    private lateinit var server: MockWebServer
    private lateinit var hubspot: HubSpot

    @BeforeTest
    fun setup() {
        server = MockWebServer()
        server.start()
        hubspot = HubSpot(
            token = "test-token",
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `sendEvent posts behavioral event identified by email`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        hubspot.sendEvent(
            eventName = "pe123_signed_up",
            email = "user@example.com",
            properties = buildJsonObject { put("plan", "pro") },
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.url.encodedPath.endsWith("/events/v3/send"))
        assertEquals("Bearer test-token", request.headers["Authorization"])

        val body = request.body!!.utf8()
        assertTrue(body.contains("\"eventName\":\"pe123_signed_up\""))
        assertTrue(body.contains("\"email\":\"user@example.com\""))
        assertTrue(body.contains("\"properties\":{\"plan\":\"pro\"}"))
    }

    @Test
    fun `sendEvent includes objectId and occurredAt when provided`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).build())

        hubspot.sendEvent(
            eventName = "pe123_viewed",
            objectId = "contact-123",
            occurredAt = JOffsetDateTime.parse("2026-06-07T12:00:30Z"),
        )

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("\"objectId\":\"contact-123\""))
        assertTrue(body.contains("\"occurredAt\":\"2026-06-07T12:00:30Z\""))
    }

    @Test
    fun `sendEvent throws on non-2xx response`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("Content-Type", "application/json")
                .body("""{"message":"Event pe123_bad not found"}""")
                .build()
        )

        val exception = assertFailsWith<Exception> {
            hubspot.sendEvent(eventName = "pe123_bad", email = "user@example.com")
        }
        assertTrue(exception.message?.contains("Event pe123_bad not found") == true)
    }

    @Test
    fun `sendEvent requires a contact identifier`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            hubspot.sendEvent(eventName = "pe123_signed_up")
        }
    }
}
