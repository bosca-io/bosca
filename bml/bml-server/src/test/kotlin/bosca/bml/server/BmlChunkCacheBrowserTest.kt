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
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.CallMiddleware
import bosca.server.netty.NettyServerEngine
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class BmlChunkCacheBrowserTest {
    @Test
    fun `content addressed modules remain cached across deployments and changed graphs load new bytes`() {
        val chrome = File(checkNotNull(System.getProperty("bml.test.browser")))
        check(chrome.isFile && chrome.canExecute()) { "Missing browser: $chrome" }
        for (packaged in listOf(false, true)) {
            val directory = Files.createTempDirectory("bml-chunk-cache-").toFile()
            try {
                val log = File("build/reports/chunk-cache-browser/${if (packaged) "packaged" else "filesystem"}.log")
                BmlChunkTestBrowser(chrome, directory.resolve("browser-profile"), log).use { browser ->
                    verifyDeployments(browser, directory, packaged)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun verifyDeployments(browser: BmlChunkTestBrowser, directory: File, packaged: Boolean) {
        val port = ServerSocket(0).use { it.localPort }
        var previousResult: String? = null
        for ((deployment, version) in listOf("first-deploy", "same-assets", "changed-assets").withIndex()) {
            val entry = if (deployment == 2) "index.page-CHANGED1.js" else "index.page-ORIGINAL.js"
            val lazy = if (deployment == 2) "lazy-CHANGED1.js" else "lazy-IJKLMNOP.js"
            val scripts = mapOf(
                "prod/$entry" to """
                    import { urls } from './chunks/shared-ABCDEFGH.js';
                    const lazy = await import('./chunks/$lazy');
                    const library = await import('/_bml/libraries/key/modules/widget-ABCDEFGH.js');
                    document.getElementById('result').textContent = JSON.stringify([import.meta.url, ...urls, lazy.url, ...library.urls]);
                    document.getElementById('result').dataset.complete = 'true';
                """.trimIndent(),
                "prod/chunks/shared-ABCDEFGH.js" to """
                    import { url } from './nested-QRSTUVWX.js';
                    export const urls = [import.meta.url, url];
                """.trimIndent(),
                "prod/chunks/nested-QRSTUVWX.js" to "export const url = import.meta.url;",
                "prod/chunks/$lazy" to "export const url = import.meta.url;",
                "libraries/key/modules/widget-ABCDEFGH.js" to """
                    import { url } from '../chunks/library-ABCDEFGH.js';
                    export const urls = [import.meta.url, url];
                """.trimIndent(),
                "libraries/key/chunks/library-ABCDEFGH.js" to "export const url = import.meta.url;",
            )
            val files = scripts + ("prod/bml-imports.json" to """{"index.page.js":["$entry","chunks/shared-ABCDEFGH.js"]}""")
            val clientDir = if (packaged) null else directory.resolve("js").apply {
                deleteRecursively(); mkdirs()
                files.filterKeys { it.startsWith("prod/") }.forEach { (path, body) ->
                    resolve(path.removePrefix("prod/")).apply { parentFile.mkdirs() }.writeText(body)
                }
            }
            val resources = files.filterKeys { packaged || it.startsWith("libraries/") }
            val manifest = Json.encodeToString(BmlClientManifest.serializer(), BmlClientManifest(files = resources.map { (path, body) ->
                BmlAssetFile(path, ContentDigest.sha256(body))
            }))
            val applicationAssets = BmlApplicationAssets("app", emptyMap(), emptyMap()) { path ->
                if (path == "app/client-manifest.json") manifest.encodeToByteArray()
                else resources[path.removePrefix("app/")]?.encodeToByteArray()
            }
            val expectedPaths = scripts.keys.map { path ->
                if (path.startsWith("prod/")) BmlClientAssets.URL_PREFIX + path.removePrefix("prod/") else "/_bml/$path"
            }.toSet()
            val requests = ConcurrentLinkedQueue<Pair<String, String?>>()
            val page = object : BmlPageRenderer, BmlBundledPage {
                override val route = "/"
                override val clientModule = "Page.js"
                override val assets = applicationAssets
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><head></head><body><pre id=\"result\"></pre></body></html>")
                }
            }
            val server = BmlServer(CompiledProject("chunks", version), listOf(page), port, dev = false, clientDir = clientDir)
            val app = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n".byteInputStream()))
            app.install(object : CallMiddleware {
                override suspend fun beforeCall(call: ServerCall) {
                    if (call.request.path.endsWith(".js")) requests.add(call.request.path to call.request.queryParameters["_ts"])
                }
            })
            server.install(app)
            app.freezeMiddleware()
            val engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-chunk-cache-browser") { engine.start() }
            try {
                awaitServer(port)
                val html = HttpClient.newHttpClient().use { client ->
                    client.send(HttpRequest.newBuilder(URI("http://localhost:$port/")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
                }
                assertFalse("importmap" in html, html)
                assertFalse("_ts=" in html, html)
                val result = browser.result("http://localhost:$port/?visit=$version")
                val urls = Json.parseToJsonElement(result).jsonArray.map { URI(it.jsonPrimitive.content) }
                assertEquals(expectedPaths, urls.map { it.path }.toSet())
                urls.forEach { assertEquals(null, it.rawQuery, it.toString()) }
                when (deployment) {
                    0 -> assertEquals(expectedPaths, requests.map { it.first }.toSet())
                    1 -> {
                        assertEquals(previousResult, result)
                        assertTrue(requests.isEmpty(), "Unchanged modules survive a deployment: $requests")
                    }
                    2 -> assertEquals(setOf("/_bml/js/$entry", "/_bml/js/chunks/$lazy"), requests.map { it.first }.toSet())
                }
                requests.clear()
                val cached = browser.result("http://localhost:$port/?visit=$version-cached")
                assertEquals(result, cached, "Cached navigation executes the same complete module graph")
                assertTrue(requests.isEmpty(), "The retained browser cache reuses modules within a deployment: $requests")
                previousResult = result
            } finally {
                engine.stopWithoutHalting()
            }
        }
    }

    private fun awaitServer(port: Int) {
        repeat(100) {
            try {
                java.net.Socket("localhost", port).close()
                return
            } catch (_: java.io.IOException) {
                Thread.sleep(50)
            }
        }
        error("Browser fixture did not start on $port")
    }
}
