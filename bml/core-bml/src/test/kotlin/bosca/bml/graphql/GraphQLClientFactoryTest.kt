package bosca.bml.graphql

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphQLClientFactoryTest {

    @Test
    fun `shared factory keeps analytics sessions scoped to each request and omits missing sessions`() {
        val seen = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            seen += exchange.requestHeaders.getFirst("X-BA-Session-ID")
            val bytes = """{"data":{}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val factory = GraphQLClientFactory("http://127.0.0.1:${server.address.port}/graphql")
            runBlocking {
                for (session in listOf("session-a", "session-b", null, " ")) {
                    factory.forToken(null, "installation", session).execute("{ ok }")
                    factory.forward("""{"query":"{ ok }"}""", null, null, "installation", session)
                }
            }
            assertEquals(listOf("session-a", "session-a", "session-b", "session-b", null, null, null, null), seen)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `site identity accompanies installation on both SSR and proxy requests`() {
        val seen = mutableListOf<List<String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            seen += listOf("X-App-ID", "X-App-Version", "X-Installation-ID").map(exchange.requestHeaders::getFirst)
            val bytes = """{"data":{"ok":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val factory = GraphQLClientFactory("http://127.0.0.1:${server.address.port}/graphql",
                defaultGraphQLHttpClient("site-app", "42"))
            runBlocking {
                factory.forToken(null, "installation-a").execute("query { ok }")
                factory.forward("""{"query":"query { ok }"}""", null, null, "installation-b")
            }
            assertEquals<List<List<String?>>>(listOf(listOf("site-app", "42", "installation-a"),
                listOf("site-app", "42", "installation-b")), seen)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `forToken forwards the request token and installation identity`() {
        val seen = mutableListOf<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            seen.add(
                exchange.requestHeaders.getFirst("Authorization") to
                    exchange.requestHeaders.getFirst("X-Installation-ID")
            )
            val bytes = """{"data":{"ok":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            // One factory (shared OkHttp pool) mints per-request clients bound to distinct tokens.
            val factory = GraphQLClientFactory("http://127.0.0.1:${server.address.port}/graphql")
            runBlocking {
                val a = factory.forToken("alice", "installation-a").execute("query { ok }")
                factory.forToken("bob", "installation-b").execute("query { ok }")
                assertEquals("true", a.jsonObject["ok"]?.jsonPrimitive?.content)
            }
            assertEquals(
                listOf<Pair<String?, String?>>(
                    "Bearer alice" to "installation-a",
                    "Bearer bob" to "installation-b",
                ),
                seen,
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `forwardGet optionally attaches the caller token without following redirects`() {
        var authorization: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/oauth2/google/connect") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            exchange.responseHeaders.add("Location", "https://accounts.example/authorize")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val factory = GraphQLClientFactory("http://127.0.0.1:${server.address.port}/graphql")
            val response = runBlocking {
                factory.forwardGet("/oauth2/google/connect?redirect=%2Fsecurity", "session-token")
            }
            assertEquals(302, response.status)
            assertEquals("Bearer session-token", authorization)
            assertEquals("https://accounts.example/authorize", response.headers.single().second)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `forward relays every upstream Set-Cookie header`() {
        var installationId: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/graphql") { exchange ->
            installationId = exchange.requestHeaders.getFirst("X-Installation-ID")
            exchange.responseHeaders.add("Set-Cookie", "_bat=; Domain=example.com; Path=/; Max-Age=0")
            exchange.responseHeaders.add("Set-Cookie", "_bat=; Domain=admin.example.com; Path=/; Max-Age=0")
            val bytes = """{"data":{"ok":true}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val factory = GraphQLClientFactory("http://127.0.0.1:${server.address.port}/graphql")
            val response = runBlocking {
                factory.forward(
                    """{"query":"{ ok }"}""",
                    null,
                    requestOrigin = null,
                    installationId = "installation-1",
                )
            }

            assertEquals(
                listOf(
                    "Set-Cookie" to "_bat=; Domain=example.com; Path=/; Max-Age=0",
                    "Set-Cookie" to "_bat=; Domain=admin.example.com; Path=/; Max-Age=0",
                ),
                response.headers,
            )
            assertEquals("installation-1", installationId)
        } finally {
            server.stop(0)
        }
    }

}
