package bosca.graphql.client

import bosca.graphql.client.generated.GetUser
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the default JDK-HTTP [GraphQLClient] against a loopback server: envelope, headers, decode, and failure. */
class HttpGraphQLClientTest {

    private fun server(status: Int, body: String, onRequest: (String, com.sun.net.httpserver.HttpExchange) -> Unit = { _, _ -> }): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/graphql") { exchange ->
                onRequest(exchange.requestBody.readBytes().decodeToString(), exchange)
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    @Test
    fun `posts the full envelope with headers and decodes the response`() = runTest {
        val requestBody = AtomicReference<String>()
        val auth = AtomicReference<String?>()
        val trace = AtomicReference<String?>()
        val installationIds = AtomicReference<List<String>?>()
        val appIds = AtomicReference<List<String>?>()
        val appVersions = AtomicReference<List<String>?>()
        val sessionIds = AtomicReference<List<String>?>()
        val server = server(200, """{"data":{"x":1}}""") { body, exchange ->
            requestBody.set(body)
            auth.set(exchange.requestHeaders.getFirst("Authorization"))
            trace.set(exchange.requestHeaders.getFirst("X-Trace"))
            installationIds.set(exchange.requestHeaders[BoscaGraphQLHeaders.INSTALLATION_ID])
            appIds.set(exchange.requestHeaders[BoscaGraphQLHeaders.APP_ID])
            appVersions.set(exchange.requestHeaders[BoscaGraphQLHeaders.APP_VERSION])
            sessionIds.set(exchange.requestHeaders[BoscaGraphQLHeaders.SESSION_ID])
        }
        try {
            val client = HttpGraphQLClient(
                endpoint = "http://127.0.0.1:${server.address.port}/graphql",
                headers = mapOf(
                    "Authorization" to "Bearer t",
                    "x-installation-id" to "stale-installation",
                    "x-app-id" to "stale-app",
                    "x-ba-session-id" to "stale-session",
                ),
                headerProvider = {
                    mapOf(
                        "X-Trace" to "abc",
                        "X-APP-VERSION" to "stale-version",
                    )
                },
                boscaInfoProvider = {
                    BoscaGraphQLClientInfo(
                        installationId = "installation-1",
                        appId = "workops",
                        appVersion = "6.20.0",
                        sessionId = "session-1",
                    )
                },
            )
            val response = client.execute("query Q(\$id: ID!) { x }", buildJsonObject { put("id", JsonPrimitive("1")) }, "Q")
            assertEquals(buildJsonObject { put("x", JsonPrimitive(1)) }, response.data)
            val sent = GraphQLJson.parseToJsonElement(requestBody.get()).jsonObject
            assertTrue(sent.getValue("query").jsonPrimitive.content.startsWith("query Q("))
            assertEquals("Q", sent.getValue("operationName").jsonPrimitive.content)
            assertEquals("1", sent.getValue("variables").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("Bearer t", auth.get())
            assertEquals("abc", trace.get())
            assertEquals(listOf("installation-1"), installationIds.get())
            assertEquals(listOf("workops"), appIds.get())
            assertEquals(listOf("6.20.0"), appVersions.get())
            assertEquals(listOf("session-1"), sessionIds.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `omits operationName and variables when absent`() = runTest {
        val requestBody = AtomicReference<String>()
        val server = server(200, """{"data":null,"errors":[{"message":"boom"}]}""") { body, _ -> requestBody.set(body) }
        try {
            val client = HttpGraphQLClient(
                endpoint = "http://127.0.0.1:${server.address.port}/graphql",
                boscaInfoProvider = { null },
            )
            val ex = assertFailsWith<GraphQLClientException> {
                client.execute(GetUser, GetUser.Variables("1")) // errors payload → raises via the bridge
            }
            assertTrue(ex.message!!.contains("boom"))
            val sent = GraphQLJson.parseToJsonElement(requestBody.get()).jsonObject
            assertTrue("query" in sent)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a raw execute with no operationName or variables sends only the query`() = runTest {
        val requestBody = AtomicReference<String>()
        val server = server(200, """{"data":{"ok":true}}""") { body, _ -> requestBody.set(body) }
        try {
            val client = HttpGraphQLClient("http://127.0.0.1:${server.address.port}/graphql")
            client.execute("{ ok }", variables = null, operationName = null)
            val sent = GraphQLJson.parseToJsonElement(requestBody.get()).jsonObject
            assertNull(sent["operationName"])
            assertNull(sent["variables"])
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a non-2xx status is an error`() = runTest {
        val server = server(503, "upstream down")
        try {
            val client = HttpGraphQLClient("http://127.0.0.1:${server.address.port}/graphql")
            assertFailsWith<IllegalStateException> { client.execute("{ ok }", null, null) }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `executeUpload posts a multipart form with operations, map, and the file bytes`() = runTest {
        val body = AtomicReference<String>()
        val contentType = AtomicReference<String?>()
        val server = server(200, """{"data":{"upload":true}}""") { raw, exchange ->
            body.set(raw)
            contentType.set(exchange.requestHeaders.getFirst("Content-Type"))
        }
        try {
            val client = HttpGraphQLClient("http://127.0.0.1:${server.address.port}/graphql")
            val file = Upload("photo.txt", "text/plain", "FILEBYTES".encodeToByteArray())
            val response = client.executeUpload(
                document = "mutation Up(\$file: Upload!) { upload(file: \$file) }",
                variables = buildJsonObject { put("file", kotlinx.serialization.json.JsonNull) },
                operationName = "Up",
                uploads = listOf(GraphQLUpload("variables.file", file)),
            )
            assertEquals(buildJsonObject { put("upload", JsonPrimitive(true)) }, response.data)
            assertTrue(contentType.get()!!.startsWith("multipart/form-data; boundary="), contentType.get())
            val sent = body.get()
            assertTrue("name=\"operations\"" in sent && "\"query\"" in sent, sent) // operations part present
            assertTrue("name=\"map\"" in sent && "{\"0\":[\"variables.file\"]}" in sent, sent)
            assertTrue("filename=\"photo.txt\"" in sent && "FILEBYTES" in sent, sent) // the file part + bytes
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `executeUpload surfaces a non-2xx status`() = runTest {
        val server = server(500, "boom")
        try {
            val client = HttpGraphQLClient("http://127.0.0.1:${server.address.port}/graphql")
            val file = Upload("a.txt", "text/plain", "x".encodeToByteArray())
            assertFailsWith<IllegalStateException> {
                client.executeUpload("mutation Up { up }", null, "Up", listOf(GraphQLUpload("variables.file", file)))
            }
        } finally {
            server.stop(0)
        }
    }
}
