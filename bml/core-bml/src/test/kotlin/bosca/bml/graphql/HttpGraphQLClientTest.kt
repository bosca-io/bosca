package bosca.bml.graphql

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpGraphQLClientTest {

    @Test
    fun `executes a query, forwards the token, and parses data`() {
        var receivedBody = ""
        var auth: String? = null
        var installationId: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            receivedBody = exchange.requestBody.readBytes().decodeToString()
            auth = exchange.requestHeaders.getFirst("Authorization")
            installationId = exchange.requestHeaders.getFirst("X-Installation-ID")
            val bytes = """{"data":{"hello":"world"}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpGraphQLClient(
                "http://127.0.0.1:${server.address.port}/graphql",
                httpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
                defaultToken = "tok123",
                defaultInstallationId = "installation-1",
            )
            val data = runBlocking { client.execute("query { hello }") }
            assertEquals("world", data.jsonObject["hello"]?.jsonPrimitive?.content)
            assertTrue(receivedBody.contains("query { hello }"), receivedBody)
            assertEquals("Bearer tok123", auth)
            assertEquals("installation-1", installationId)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `throws on a graphql errors payload`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            val bytes = """{"errors":[{"message":"boom"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpGraphQLClient("http://127.0.0.1:${server.address.port}/graphql")
            val ex = assertFailsWith<GraphQLException> { runBlocking { client.execute("query { x }") } }
            assertTrue(ex.message?.contains("boom") == true, ex.message)
        } finally {
            server.stop(0)
        }
    }
}
