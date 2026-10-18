package bosca.storage.service

import com.google.cloud.NoCredentials
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real Google SDK media requests against an isolated local HTTP service. */
class GoogleObjectStorageVersionIntegrationTest {
    @Test
    fun `retained Google reads select the captured generation without a current metadata lookup`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        withEndpoint({ exchange ->
            val query = query(exchange)
            requests.add(exchange.requestURI.path to query)
            exchange.responseHeaders.add("x-goog-generation", "42")
            respond(exchange, 200, if (query["generation"] == "42") "retained" else "current")
        }) { storage ->
            assertEquals("retained", storage.getInputStream(StringObjectPath("asset"), "42").use {
                it.readAllBytes().decodeToString()
            })
        }
        assertEquals(1, requests.size)
        assertEquals("/download/storage/v1/b/bucket/o/asset", requests.single().first)
        assertEquals("42", requests.single().second["generation"])
        assertEquals("media", requests.single().second["alt"])
    }

    @Test
    fun `an expired Google generation fails without requesting the current object`(): Unit = runBlocking {
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        withEndpoint({ exchange ->
            val query = query(exchange)
            requests.add(query)
            if (query["generation"] == "42") {
                respond(exchange, 404, """{"error":{"code":404,"message":"Generation gone"}}""")
            } else {
                respond(exchange, 200, "current")
            }
        }) { storage ->
            val missing = assertFailsWith<ObjectNotFoundException> {
                storage.getInputStream(StringObjectPath("asset"), "42").use { it.readAllBytes() }
            }
            assertTrue(generateSequence(missing as Throwable) { it.cause }.any {
                it is StorageException && it.code == 404
            })
        }
        assertEquals(1, requests.size)
        assertEquals("42", requests.single()["generation"])
    }

    private suspend fun withEndpoint(
        handle: (HttpExchange) -> Unit,
        block: suspend (ObjectStorageService) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.start()
        try {
            StorageOptions.newBuilder()
                .setProjectId("test-project")
                .setHost("http://127.0.0.1:${server.address.port}")
                .setCredentials(NoCredentials.getInstance())
                .build().service.use { client ->
                    block(GoogleObjectStorageService("bucket", client, "", "", mockk(), mockk()))
                }
        } finally {
            server.stop(0)
        }
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to
                URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", if (status == 200) "application/octet-stream" else "application/json")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
