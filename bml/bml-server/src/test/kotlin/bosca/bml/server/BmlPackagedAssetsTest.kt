package bosca.bml.server

import bosca.bml.library.BmlApplicationAssets
import bosca.bml.library.BmlAssetFile
import bosca.bml.library.BmlClientManifest
import bosca.bml.project.CompiledProject
import bosca.bml.project.ContentDigest
import bosca.bml.render.BmlBundledPage
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json

class BmlPackagedAssetsTest {
    private class Fixture(val value: String) {
        val reads = AtomicInteger()
        val digest = ContentDigest.sha256(value)
        private val manifest = Json.encodeToString(
            BmlClientManifest.serializer(), BmlClientManifest(
                files = listOf(
                    BmlAssetFile("dev/main.js", digest),
                    BmlAssetFile("prod/index.page.js", digest),
                    BmlAssetFile("prod/chunks/chunk-ABCDEFGH.js", digest),
                    BmlAssetFile("libraries/key/theme.css", digest),
                )
            )
        )
        private val assets = BmlApplicationAssets("app", emptyMap(), emptyMap()) { path ->
            if (path.endsWith("client-manifest.json")) manifest.encodeToByteArray()
            else {
                reads.incrementAndGet(); value.encodeToByteArray()
            }
        }

        fun server(port: Int, development: Boolean = true) = BmlServer(CompiledProject("assets", "1"), listOf(object : BmlPageRenderer, BmlBundledPage {
            override val route = "/"
            override val clientModule = "main.js"
            override val assets = this@Fixture.assets
            override suspend fun render(ctx: RenderContext) {
                ctx.writer.markup("<html><head></head><body>assets</body></html>")
            }
        }), port, dev = development)
    }

    @Test
    fun `conditional requests skip bodies and cached response bytes belong to their generation`() {
        val port = ServerSocket(0).use { it.localPort }
        val first = Fixture("first generation")
        val server = first.server(port)
        val engine = start(server, port)
        try {
            val client = HttpClient.newHttpClient()
            fun get(path: String, etag: String? = null): HttpResponse<String> {
                val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).timeout(Duration.ofSeconds(10))
                if (etag != null) request.header("If-None-Match", etag)
                return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
            }

            val etag = "\"${first.digest.removePrefix("sha256:")}\""
            for (path in listOf("/_bml/js/main.js", "/_bml/libraries/key/theme.css")) {
                val conditional = get(path, etag)
                assertEquals(304, conditional.statusCode())
                assertEquals("", conditional.body())
                assertEquals(0, first.reads.get())
            }
            val concurrent = (1..8).map {
                client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:$port/_bml/js/main.js"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
            }
            for (future in concurrent) {
                assertEquals(200, future.join().statusCode())
                assertEquals(first.value, future.join().body())
            }
            assertEquals(1, first.reads.get(), "Concurrent first requests share one validated resource load")
            repeat(3) {
                for (path in listOf("/_bml/js/main.js", "/_bml/libraries/key/theme.css")) {
                    val response = get(path)
                    assertEquals(200, response.statusCode())
                    assertEquals(first.value, response.body())
                    assertEquals(etag, response.headers().firstValue("ETag").orElse(null))
                    assertEquals("no-cache", response.headers().firstValue("Cache-Control").orElse(null))
                }
            }
            assertEquals(2, first.reads.get(), "Each indexed body is loaded once per generation")
            assertEquals(404, get("/_bml/js/unknown.js", "*").statusCode())
            val replacement = Fixture("replacement generation")
            val rollback = server.hotSwapFrom(replacement.server(port))
            val changed = get("/_bml/js/main.js", etag)
            assertEquals(200, changed.statusCode())
            assertEquals(replacement.value, changed.body())
            assertNotEquals(etag, changed.headers().firstValue("ETag").orElse(null))
            assertEquals(1, replacement.reads.get())
            rollback()
            assertEquals(first.value, get("/_bml/js/main.js").body())
            assertEquals(2, first.reads.get(), "Rollback retains its own validated response cache")
        } finally { engine.stopWithoutHalting() }
    }

    @Test
    fun `production packaged assets are immutable only with a content hash or current deployment token`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = Fixture("production asset").server(port, development = false)
        val engine = start(server, port)
        try {
            HttpClient.newHttpClient().use { client ->
                fun get(path: String) = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                val html = get("/").body()
                val token = checkNotNull(Regex("_ts=([0-9a-f]+)").find(html)?.groupValues?.get(1)) { html }
                for (path in listOf("/_bml/js/index.page.js", "/_bml/libraries/key/theme.css")) {
                    for ((query, expected) in listOf(
                        "" to "no-cache",
                        "?_ts=old-deployment" to "no-cache",
                        "?_ts=$token" to "public, max-age=31536000, immutable",
                    )) {
                        val response = get(path + query)
                        assertEquals(200, response.statusCode(), path + query)
                        assertEquals(listOf(expected), response.headers().allValues("Cache-Control"), path + query)
                    }
                }
                val chunk = get("/_bml/js/chunks/chunk-ABCDEFGH.js")
                assertEquals(200, chunk.statusCode())
                assertEquals(listOf("public, max-age=31536000, immutable"), chunk.headers().allValues("Cache-Control"))
            }
        } finally {
            engine.stopWithoutHalting()
        }
    }

    private fun start(server: BmlServer, port: Int): NettyServerEngine {
        val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: true\n".byteInputStream()))
        server.install(app)
        app.freezeMiddleware()
        val engine = NettyServerEngine(app, port)
        thread(isDaemon = true, name = "bml-packaged-asset-test") { engine.start() }
        for (attempt in 0 until 100) {
            try {
                java.net.Socket("localhost", port).close(); return engine
            } catch (_: java.io.IOException) {
                Thread.sleep(50)
            }
        }
        engine.stopWithoutHalting()
        error("Packaged asset test server did not start")
    }
}
