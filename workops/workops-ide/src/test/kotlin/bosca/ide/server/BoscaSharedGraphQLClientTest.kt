package bosca.ide.server

import bosca.graphql.client.KtorGraphQLClient
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BoscaSharedGraphQLClientTest {
    private lateinit var server: HttpServer
    private val authorizations = mutableListOf<String?>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/graphql") { exchange ->
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            exchange.requestBody.use { it.readAllBytes() }
            val firstRequest = authorizations.size == 1
            val status = if (firstRequest) 401 else 200
            val body = if (firstRequest) "unauthorized" else """{"data":{"__typename":"Query"}}"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `shared GraphQL client retries one unauthorized response with refreshed CLI auth`() = runBlocking {
        val http = HttpClient(Java)
        try {
            installBoscaUnauthorizedRetry(http) { "fresh-token" }
            val client = KtorGraphQLClient(
                endpoint = "http://127.0.0.1:${server.address.port}/graphql",
                httpClient = http,
                headerProvider = { mapOf("Authorization" to "Bearer expired-token") },
            )

            val response = client.execute("query BoscaIdeHealth { __typename }", null, null)

            assertEquals("Query", response.data?.toString()?.let { com.google.gson.JsonParser.parseString(it) }
                ?.asJsonObject?.get("__typename")?.asString)
            assertEquals(listOf("Bearer expired-token", "Bearer fresh-token"), authorizations)
        } finally {
            http.close()
        }
    }
}
