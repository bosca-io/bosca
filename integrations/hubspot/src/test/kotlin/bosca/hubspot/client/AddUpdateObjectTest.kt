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

/**
 * Covers the HubSpot client's object create/update split (`add`/`update`, via `addContact`/
 * `updateContact`): the create path POSTs and recovers an existing id from an HTTP 409 (an
 * already-existing object returns its id rather than failing), keyed on the status code (not the
 * message text); the update path PATCHes and surfaces any error.
 */
class AddUpdateObjectTest {

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
    fun tearDown() = server.close()

    private val contact = buildJsonObject { put("email", "ada@x.io") }

    private fun enqueue(code: Int, body: String) = server.enqueue(
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    )

    @Test
    fun `add returns the new id`() = runTest {
        enqueue(201, """{"id":"777"}""")
        assertEquals("777", hubspot.addContact(contact).id)
    }

    @Test
    fun `add recovers the existing id on a 409 conflict`() = runTest {
        enqueue(409, """{"status":"error","message":"Contact already exists. Existing ID: 12345","category":"CONFLICT"}""")
        assertEquals("12345", hubspot.addContact(contact).id)
    }

    @Test
    fun `add surfaces a 409 without a recoverable id`() = runTest {
        enqueue(409, """{"status":"error","message":"Conflict","category":"CONFLICT"}""")
        assertFailsWith<Exception> { hubspot.addContact(contact) }
    }

    @Test
    fun `add never mistakes a non-409 for already-exists, even with that text`() = runTest {
        // Keying on the status (not the message) means a 400 carrying "Existing ID:" text still fails.
        enqueue(400, """{"message":"Bad request. Existing ID: 999"}""")
        assertFailsWith<Exception> { hubspot.addContact(contact) }
    }

    @Test
    fun `update patches the object and succeeds on 200`() = runTest {
        enqueue(200, """{"id":"12345"}""")
        hubspot.updateContact("12345", contact) // no exception thrown == success
    }

    @Test
    fun `update surfaces an error response`() = runTest {
        enqueue(400, """{"message":"Bad request"}""")
        assertFailsWith<Exception> { hubspot.updateContact("12345", contact) }
    }
}
