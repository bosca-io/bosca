package bosca.git.ui.server

import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real HTTP coverage of the forwarding boundary; authorization remains upstream. */
class GitFileProxyTest {
    @Test
    fun `raw image retains nested encoded path credentials MIME and security headers`() {
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l1sAAAAASUVORK5CYII=")
        val path = "/owner/repo/raw/abc/images/logo%20%231.png"
        withProxy({ exchange ->
            assertEquals(path, exchange.requestURI.rawPath)
            assertEquals("_custom=caller", exchange.requestHeaders.getFirst("Cookie"))
            assertEquals("Bearer explicit", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("https://ui.example.test", exchange.requestHeaders.getFirst("Origin"))
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
            exchange.responseHeaders.add("Content-Security-Policy", "sandbox; default-src 'none'")
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }) { origin ->
            val response = get(origin, path, "Cookie" to "_custom=caller", "Authorization" to "Bearer explicit", "Origin" to "https://ui.example.test")
            assertEquals(200, response.statusCode())
            assertEquals("image/png", response.headers().firstValue("Content-Type").orElse(""))
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertEquals("sandbox; default-src 'none'", response.headers().firstValue("Content-Security-Policy").orElse(""))
            assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(""))
            assertContentEquals(png, response.body())
        }
    }

    @Test
    fun `path normalization cannot forward file credentials to another API route`() {
        val forwarded = AtomicInteger()
        withProxy({ exchange ->
            forwarded.incrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }) { origin ->
            for (path in listOf(
                "/owner/repo/raw/abc/../../../../graphql?query=danger",
                "/owner/repo/raw/abc/%2e%2E/%2E%2e/%2e%2e/%2e%2e/graphql?query=danger",
                "/ci/artifacts/repo/run/../../../../graphql?query=danger",
            )) {
                assertEquals(400, get(origin, path, "Authorization" to "Bearer caller").statusCode(), path)
            }
            assertEquals(0, forwarded.get(), "File credentials reached an unrelated API endpoint")
        }
    }

    @Test
    fun `configured API base path applies to raw file requests`() {
        withProxy({ exchange ->
            assertEquals("/api/git/owner/repo/raw/abc/images/logo.png", exchange.requestURI.rawPath)
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("ok".toByteArray()) }
        }, apiPrefix = "/api/git") { origin ->
            val response = get(origin, "/owner/repo/raw/abc/images/logo.png")
            assertEquals(200, response.statusCode())
            assertEquals("ok", response.body().decodeToString())
        }
    }

    @Test
    fun `artifact download streams multiple chunks and retains upstream rejection`() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val path = "/ci/artifacts/repo/run/build%2Freport%20%231.zip"
        withProxy({ exchange ->
            assertEquals(path, exchange.requestURI.rawPath)
            if (exchange.requestHeaders.getFirst("Cookie") != "_bat=caller") {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
            } else {
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.responseHeaders.add("Content-Disposition", "attachment")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    for (offset in bytes.indices step 65536) {
                        output.write(bytes, offset, minOf(65536, bytes.size - offset))
                    }
                }
            }
        }) { origin ->
            assertEquals(403, get(origin, path).statusCode())
            val response = get(origin, path, "Cookie" to "_bat=caller")
            assertEquals(200, response.statusCode())
            assertEquals("attachment", response.headers().firstValue("Content-Disposition").orElse(""))
            assertContentEquals(bytes, response.body())
        }
    }

    @Test
    fun `download begins before the upstream has finished producing the artifact`() {
        val release = CountDownLatch(1)
        val first = ByteArray(65536) { 1 }
        val last = ByteArray(65536) { 2 }
        try {
            withProxy({ exchange ->
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    output.write(first)
                    output.flush()
                    assertTrue(release.await(10, TimeUnit.SECONDS), "UI buffered the upstream response")
                    output.write(last)
                }
            }) { origin ->
                val request = HttpRequest.newBuilder(URI.create("$origin/ci/artifacts/repo/run/output.zip"))
                    .timeout(Duration.ofSeconds(10)).build()
                val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { input ->
                    assertEquals(200, response.statusCode())
                    assertContentEquals(first, input.readNBytes(first.size))
                    release.countDown()
                    assertContentEquals(last, input.readAllBytes())
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `invalid explicit credentials remain authoritative and are never replaced by cookies`() {
        withProxy({ exchange ->
            assertEquals("Bearer invalid", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("_bat=valid-cookie", exchange.requestHeaders.getFirst("Cookie"))
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }) { origin ->
            assertEquals(401, get(origin, "/owner/repo/raw/main/README.md", "Authorization" to "Bearer invalid", "Cookie" to "_bat=valid-cookie").statusCode())
        }
    }

    private fun withProxy(handler: (HttpExchange) -> Unit, apiPrefix: String = "", check: (String) -> Unit) {
        val upstream = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        upstream.createContext("/", handler)
        upstream.start()
        try {
            val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
            GitFileProxy("http://localhost:${upstream.address.port}$apiPrefix/graphql").install(application)
            application.router.get("/ready") { call.respond("ready") }
            val port = ServerSocket(0).use { it.localPort }
            val engine = NettyServerEngine(application, port)
            thread(isDaemon = true) { engine.start() }
            try {
                val origin = "http://localhost:$port"
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                while (System.nanoTime() < deadline && runCatching { get(origin, "/ready") }.isFailure) Thread.sleep(25)
                assertEquals(200, get(origin, "/ready").statusCode())
                check(origin)
            } finally {
                engine.stopWithoutHalting()
            }
        } finally {
            upstream.stop(0)
        }
    }

    private fun get(origin: String, path: String, vararg headers: Pair<String, String>): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10))
        headers.forEach { (name, value) -> request.header(name, value) }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
}
