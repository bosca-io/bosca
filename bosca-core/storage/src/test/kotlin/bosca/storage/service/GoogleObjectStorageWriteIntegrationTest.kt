package bosca.storage.service

import com.google.cloud.NoCredentials
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Uses the real Google SDK to verify streaming uploads and preserved content settings. */
class GoogleObjectStorageWriteIntegrationTest {
    @Test
    fun `upload preserves content settings and reports actual consumed bytes`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        var uploaded = ""
        var settings = ""
        withEndpoint({ exchange, endpoint ->
            requests.add(exchange.requestMethod to exchange.requestURI.path)
            when {
                exchange.requestMethod == "GET" && exchange.requestURI.path.startsWith("/storage/") ->
                    respond(exchange, 200, metadata("40", "old", 3))
                exchange.requestMethod == "POST" -> {
                    settings = exchange.requestBody.readBytes().decodeToString()
                    exchange.responseHeaders.add("Location", "$endpoint/session")
                    respond(exchange, 200, "")
                }
                exchange.requestMethod == "PUT" -> {
                    uploaded = exchange.requestBody.readBytes().decodeToString()
                    respond(exchange, 200, metadata("42", "written-etag", uploaded.toByteArray().size))
                }
                else -> respond(exchange, 404, "")
            }
        }) { storage ->
            val count = storage.setInputStream(StringObjectPath("asset"), "written".byteInputStream())
            assertEquals(7L, count)
            assertEquals("written", uploaded)
            assertTrue(settings.contains("text/plain"))
            assertTrue(settings.contains("preserved"))
            assertEquals(1, requests.count { it.first == "GET" })
        }
        assertEquals(1, requests.count { it.first == "POST" })
        assertEquals(1, requests.count { it.first == "PUT" })
        assertEquals(1, requests.count { it.second.startsWith("/storage/") })
    }

    @Test
    fun `failed finalization has no successful result or fallback current metadata lookup`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        withEndpoint({ exchange, endpoint ->
            requests.add(exchange.requestMethod)
            when (exchange.requestMethod) {
                "GET" -> respond(exchange, 404, """{"error":{"code":404,"message":"Not found"}}""")
                "POST" -> {
                    exchange.requestBody.readBytes()
                    exchange.responseHeaders.add("Location", "$endpoint/session")
                    respond(exchange, 200, "")
                }
                else -> {
                    exchange.requestBody.readBytes()
                    respond(exchange, 403, """{"error":{"code":403,"message":"Write denied"}}""")
                }
            }
        }) { storage ->
            val failure = assertFailsWith<StorageException> {
                storage.setInputStream(StringObjectPath("asset"), "written".byteInputStream())
            }
            assertEquals(403, failure.code)
        }
        assertEquals(1, requests.count { it == "GET" })
        assertEquals(1, requests.count { it == "PUT" })
    }

    private suspend fun withEndpoint(
        handle: (HttpExchange, String) -> Unit,
        block: suspend (ObjectStorageService) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val endpoint = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange -> exchange.use { handle(it, endpoint) } }
        server.start()
        try {
            StorageOptions.newBuilder().setProjectId("test-project").setHost(endpoint)
                .setCredentials(NoCredentials.getInstance()).build().service.use { client ->
                    block(GoogleObjectStorageService("bucket", client, "", "", mockk(), mockk()))
                }
        } finally {
            server.stop(0)
        }
    }

    private fun metadata(generation: String, etag: String, size: Int) = """
        {"kind":"storage#object","bucket":"bucket","name":"asset","size":"$size",
         "generation":"$generation","etag":"$etag","updated":"2026-10-19T10:00:00Z",
         "contentType":"text/plain","metadata":{"setting":"preserved"}}
    """.trimIndent()

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
