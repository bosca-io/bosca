package bosca.analytics.delivery

import bosca.core.preferences.Preferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallationIdProviderTest {
    @Test
    fun `anonymous provider retains a stable process identity`() = runTest {
        val anonymous = AnonymousInstallationIdProvider()
        val first = anonymous.getOrCreate()
        assertTrue(first.isNotBlank())
        assertEquals(first, anonymous.getOrCreate())
    }

    @Test
    fun `HTTP installation provider reuses a nonblank stored identifier`() = runTest {
        var calls = 0
        val provider = HttpInstallationIdProvider(
            collectorUrl = "https://analytics.test/",
            client = HttpClient(MockEngine {
                calls++
                respond("not used")
            }),
            preferences = FakePreferences("__iid" to "existing"),
        )

        assertEquals("existing", provider.getOrCreate())
        assertEquals(0, calls)
    }

    @Test
    fun `HTTP installation provider registers and persists a missing identifier`() = runTest {
        var requestedUrl = ""
        var requestedAppId: String? = null
        var requestedAppVersion: String? = null
        val preferences = FakePreferences("__iid" to " ")
        val provider = HttpInstallationIdProvider(
            config = BoscaSinkConfig(
                url = "https://analytics.test/",
                appId = "reader",
                appVersion = "2.3.4",
                clientId = "test",
            ),
            client = HttpClient(MockEngine { request ->
                requestedUrl = request.url.toString()
                requestedAppId = request.headers[BoscaRequestHeaders.APP_ID]
                requestedAppVersion = request.headers[BoscaRequestHeaders.APP_VERSION]
                respond("""{"id":"issued","ignored":true}""", HttpStatusCode.Created)
            }),
            preferences = preferences,
        )

        assertEquals("issued", provider.getOrCreate())
        assertEquals("issued", preferences.getString("__iid").first())
        assertEquals("https://analytics.test/installation", requestedUrl)
        assertEquals("reader", requestedAppId)
        assertEquals("2.3.4", requestedAppVersion)
    }

    @Test
    fun `HTTP installation provider rejects failed and empty registrations`() = runTest {
        val failed = HttpInstallationIdProvider(
            "https://analytics.test",
            HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }),
            FakePreferences(),
        )
        assertTrue(assertFailsWith<IllegalStateException> { failed.getOrCreate() }.message.orEmpty().contains("HTTP 503"))

        val empty = HttpInstallationIdProvider(
            "https://analytics.test",
            HttpClient(MockEngine { respond("""{"id":""}""", HttpStatusCode.OK) }),
            FakePreferences(),
        )
        assertTrue(assertFailsWith<IllegalStateException> { empty.getOrCreate() }.message.orEmpty().contains("empty"))
    }

    @Test
    fun `installation response requires an identifier`() {
        assertEquals(
            InstallationResponse("iid"),
            Json.decodeFromString(InstallationResponse.serializer(), """{"id":"iid"}"""),
        )
        assertFailsWith<SerializationException> { Json.decodeFromString(InstallationResponse.serializer(), "{}") }
    }

    private class FakePreferences(vararg initial: Pair<String, String?>) : Preferences {
        private val values = initial.associate { (key, value) -> key to MutableStateFlow(value) }.toMutableMap()

        override fun getString(key: String): Flow<String?> =
            values.getOrPut(key) { MutableStateFlow(null) }.asStateFlow()

        override suspend fun setString(key: String, value: String?) {
            values.getOrPut(key) { MutableStateFlow(null) }.value = value
        }
    }
}
