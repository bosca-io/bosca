package bosca.graphql.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives [KtorGraphQLClient] over Ktor's MockEngine — no real network/engine. */
class KtorGraphQLClientTest {

    private val jsonCt = headersOf(HttpHeaders.ContentType, "application/json")

    private fun bodyText(request: HttpRequestData): String = (request.body as TextContent).text

    @Test
    fun `execute sends the envelope with merged headers and parses data`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("""{"data":{"x":1}}""", HttpStatusCode.OK, jsonCt)
        }
        val gql = KtorGraphQLClient(
            endpoint = "https://x/graphql",
            httpClient = HttpClient(engine),
            headers = mapOf(
                "X-Static" to "s",
                "x-installation-id" to "stale-installation",
                "x-app-id" to "stale-app",
                "x-ba-session-id" to "stale-session",
            ),
            headerProvider = {
                mapOf(
                    "Authorization" to "Bearer t",
                    "X-APP-VERSION" to "stale-version",
                )
            },
            boscaInfoProvider = {
                BoscaGraphQLClientInfo(
                    installationId = "installation-1",
                    appId = "reader",
                    appVersion = "6.20.0",
                    sessionId = "session-1",
                )
            },
        )

        val resp = gql.execute("query Q { x }", buildJsonObject { put("a", JsonPrimitive(1)) }, "Q")

        assertNull(resp.errors)
        assertEquals(1, resp.data!!.jsonObject["x"]!!.jsonPrimitive.int)
        val req = requests.single()
        // static + dynamic headers both present (dynamic added last → wins on collision), plus Accept
        assertEquals("s", req.headers["X-Static"])
        assertEquals("Bearer t", req.headers["Authorization"])
        assertEquals("installation-1", req.headers[BoscaGraphQLHeaders.INSTALLATION_ID])
        assertEquals("reader", req.headers[BoscaGraphQLHeaders.APP_ID])
        assertEquals("6.20.0", req.headers[BoscaGraphQLHeaders.APP_VERSION])
        assertEquals(listOf("installation-1"), req.headers.getAll(BoscaGraphQLHeaders.INSTALLATION_ID))
        assertEquals(listOf("reader"), req.headers.getAll(BoscaGraphQLHeaders.APP_ID))
        assertEquals(listOf("6.20.0"), req.headers.getAll(BoscaGraphQLHeaders.APP_VERSION))
        assertEquals(listOf("session-1"), req.headers.getAll(BoscaGraphQLHeaders.SESSION_ID))
        assertEquals("application/json", req.headers[HttpHeaders.Accept])
        // envelope carries query + operationName + variables
        val body = bodyText(req)
        assertTrue(body.contains("\"query\""))
        assertTrue(body.contains("\"operationName\":\"Q\""))
        assertTrue(body.contains("\"variables\""))
    }

    @Test
    fun `execute omits operationName and variables when absent and uses default headers`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond("""{"data":{}}""", HttpStatusCode.OK, jsonCt)
        }
        // default headers (empty) + default headerProvider ({ emptyMap() })
        val gql = KtorGraphQLClient(
            endpoint = "https://x/graphql",
            httpClient = HttpClient(engine),
            boscaInfoProvider = { null },
        )

        val resp = gql.execute("query { x }", null, null)

        assertNull(resp.errors)
        val req = requests.single()
        val body = bodyText(req)
        assertTrue(body.contains("\"query\""))
        assertTrue(!body.contains("operationName"))
        assertTrue(!body.contains("variables"))
        assertEquals("application/json", req.headers[HttpHeaders.Accept])
    }

    @Test
    fun `execute returns the errors envelope`() = runTest {
        val engine = MockEngine { respond("""{"errors":[{"message":"boom"}]}""", HttpStatusCode.OK, jsonCt) }

        val resp = KtorGraphQLClient("https://x/graphql", HttpClient(engine)).execute("query { x }", null, null)

        assertNull(resp.data)
        assertEquals("boom", resp.errors!!.single().message)
    }

    @Test
    fun `execute throws on a non-2xx response`() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }

        val ex = assertFailsWith<IllegalStateException> {
            KtorGraphQLClient("https://x/graphql", HttpClient(engine)).execute("query { x }", null, null)
        }
        assertTrue(ex.message!!.contains("HTTP 500"))
    }

    @Test
    fun `executeUpload sends a multipart request and parses data`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            capturedRequest = request
            respond("""{"data":{"ok":true}}""", HttpStatusCode.OK, jsonCt)
        }
        val gql = KtorGraphQLClient(
            endpoint = "https://x/graphql",
            httpClient = HttpClient(engine),
            headers = mapOf(
                "x-installation-id" to "stale-installation",
                "x-app-id" to "stale-app",
                "x-ba-session-id" to "stale-session",
            ),
            headerProvider = { mapOf("Authorization" to "Bearer t") },
            boscaInfoProvider = {
                BoscaGraphQLClientInfo(
                    installationId = "installation-1",
                    appId = "reader",
                    appVersion = "6.20.0",
                    sessionId = "session-1",
                )
            },
        )
        val upload = Upload("a.txt", "text/plain", "hi".encodeToByteArray())

        val resp = gql.executeUpload(
            document = "mutation(\$f: Upload!) { up(file: \$f) }",
            variables = buildJsonObject { put("f", JsonNull) },
            operationName = null,
            uploads = listOf(GraphQLUpload("variables.f", upload)),
        )

        assertNull(resp.errors)
        assertTrue(resp.data!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
        val request = requireNotNull(capturedRequest)
        assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
        assertEquals("Bearer t", request.headers["Authorization"])
        assertEquals(listOf("installation-1"), request.headers.getAll(BoscaGraphQLHeaders.INSTALLATION_ID))
        assertEquals(listOf("reader"), request.headers.getAll(BoscaGraphQLHeaders.APP_ID))
        assertEquals(listOf("6.20.0"), request.headers.getAll(BoscaGraphQLHeaders.APP_VERSION))
        assertEquals(listOf("session-1"), request.headers.getAll(BoscaGraphQLHeaders.SESSION_ID))
    }
}
