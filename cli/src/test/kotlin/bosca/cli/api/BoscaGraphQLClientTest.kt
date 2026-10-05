package bosca.cli.api

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.GraphQLClientException
import bosca.graphql.client.execute
import bosca.graphql.gen.ClearCache
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the CLI's Bosca-native [BoscaGraphQLClient] over a loopback server with real OkHttp calls. Covers the
 * envelope, bearer attach, the 401 force-refresh-and-retry, and the error / non-2xx ([BoscaHttpException]) paths.
 */
class BoscaGraphQLClientTest {

    private val http = OkHttpClient()

    /** A loopback `/graphql` server returning queued (status, body) pairs in order, recording each request. */
    private class Fixture(private val responses: List<Pair<Int, String>>) {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authHeaders = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        private val next = AtomicInteger(0)

        init {
            server.createContext("/graphql") { exchange ->
                authHeaders.add(exchange.requestHeaders.getFirst("Authorization"))
                bodies.add(exchange.requestBody.readBytes().decodeToString())
                val (status, body) = responses[next.getAndIncrement()]
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/graphql"
    }

    private fun client(f: Fixture, token: String? = "tok", onUnauthorized: suspend () -> String? = { null }) =
        BoscaGraphQLClient(f.url, http, token = { token }, onUnauthorized = onUnauthorized)

    @Test
    fun `posts the envelope with the bearer token and decodes the typed data`() = runBlocking {
        val f = Fixture(listOf(200 to """{"data":{"clearCache":true}}"""))
        try {
            val data = client(f).execute(ClearCache, Unit)
            assertTrue(data.clearCache)
            assertEquals("Bearer tok", f.authHeaders.single())
            val sent = GraphQLJson.parseToJsonElement(f.bodies.single()).jsonObject
            assertEquals("ClearCache", sent.getValue("operationName").jsonPrimitive.content)
            assertTrue(sent.getValue("query").jsonPrimitive.content.startsWith("mutation ClearCache"))
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `on 401 it force-refreshes once and retries with the new token`() = runBlocking {
        val f = Fixture(listOf(401 to "unauthorized", 200 to """{"data":{"clearCache":true}}"""))
        try {
            val data = client(f, token = "stale", onUnauthorized = { "fresh" }).execute(ClearCache, Unit)
            assertTrue(data.clearCache)
            assertEquals(listOf<String?>("Bearer stale", "Bearer fresh"), f.authHeaders)
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `on 401 it surfaces the original when refresh returns null`() = runBlocking {
        val f = Fixture(listOf(401 to "nope"))
        try {
            val ex = assertFailsWith<BoscaHttpException> {
                client(f, token = "stale", onUnauthorized = { null }).execute(ClearCache, Unit)
            }
            assertEquals(401, ex.statusCode)
            assertEquals(listOf<String?>("Bearer stale"), f.authHeaders) // no retry
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `on 401 it does not retry when refresh returns the same token`() = runBlocking {
        val f = Fixture(listOf(401 to "nope"))
        try {
            assertFailsWith<BoscaHttpException> {
                client(f, token = "same", onUnauthorized = { "same" }).execute(ClearCache, Unit)
            }
            assertEquals(listOf<String?>("Bearer same"), f.authHeaders)
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `on 401 it surfaces the original when refresh throws`() = runBlocking {
        val f = Fixture(listOf(401 to "nope"))
        try {
            assertFailsWith<BoscaHttpException> {
                client(f, token = "stale", onUnauthorized = { throw RuntimeException("refresh revoked") })
                    .execute(ClearCache, Unit)
            }
            assertEquals(listOf<String?>("Bearer stale"), f.authHeaders)
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `an errors envelope raises through the typed bridge`() = runBlocking {
        val f = Fixture(listOf(200 to """{"data":null,"errors":[{"message":"boom"}]}"""))
        try {
            val ex = assertFailsWith<GraphQLClientException> { client(f).execute(ClearCache, Unit) }
            assertTrue(ex.message!!.contains("boom"))
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `a non-2xx, non-401 status is an error`() = runBlocking {
        val f = Fixture(listOf(503 to "upstream down"))
        try {
            val ex = assertFailsWith<BoscaHttpException> { client(f).execute(ClearCache, Unit) }
            assertEquals(503, ex.statusCode)
            assertTrue(ex.message!!.contains("503"))
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `no token omits the Authorization header`() = runBlocking {
        val f = Fixture(listOf(200 to """{"data":{"clearCache":true}}"""))
        try {
            client(f, token = null).execute(ClearCache, Unit)
            assertNull(f.authHeaders.single())
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `executeUpload posts a multipart form carrying operations, map, and the file bytes`() = runBlocking {
        val f = Fixture(listOf(200 to """{"data":{"upload":true}}"""))
        try {
            val file = bosca.graphql.client.Upload("photo.txt", "text/plain", "FILEBYTES".encodeToByteArray())
            val variables = kotlinx.serialization.json.JsonObject(mapOf("file" to kotlinx.serialization.json.JsonNull))
            val resp = client(f).executeUpload(
                document = "mutation Up(\$file: Upload!) { upload(file: \$file) }",
                variables = variables,
                operationName = "Up",
                uploads = listOf(bosca.graphql.client.GraphQLUpload("variables.file", file)),
            )
            assertEquals("true", resp.data!!.jsonObject.getValue("upload").jsonPrimitive.content)
            val sent = f.bodies.single()
            assertTrue("name=\"operations\"" in sent, sent)
            assertTrue("name=\"map\"" in sent && "{\"0\":[\"variables.file\"]}" in sent, sent)
            assertTrue("filename=\"photo.txt\"" in sent && "FILEBYTES" in sent, sent)
        } finally {
            f.server.stop(0)
        }
    }

    @Test
    fun `a raw execute with no operationName or variables sends only the query`() = runBlocking {
        val f = Fixture(listOf(200 to """{"data":{"ok":true}}"""))
        try {
            client(f).execute("{ ok }", variables = null, operationName = null)
            val sent = GraphQLJson.parseToJsonElement(f.bodies.single()).jsonObject
            assertNull(sent["operationName"])
            assertNull(sent["variables"])
            assertTrue("query" in sent)
        } finally {
            f.server.stop(0)
        }
    }

}
