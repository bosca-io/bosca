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
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class BmlChunkCacheBrowserTest {
    @Test
    fun `new deployments download unchanged shared nested lazy and library modules with a new token`() {
        val chrome = File(checkNotNull(System.getProperty("bml.test.browser")))
        check(chrome.isFile && chrome.canExecute()) { "Missing browser: $chrome" }
        for (packaged in listOf(false, true)) {
            val directory = Files.createTempDirectory("bml-chunk-cache-").toFile()
            try {
                verifyDeployments(chrome, directory, packaged)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun verifyDeployments(chrome: File, directory: File, packaged: Boolean) {
        val scripts = mapOf(
            "prod/index.page.js" to """
                import { urls } from './chunks/shared-ABCDEFGH.js';
                const lazy = await import('./chunks/lazy-IJKLMNOP.js');
                const library = await import('/_bml/libraries/key/modules/widget.js');
                document.getElementById('result').textContent = JSON.stringify([import.meta.url, ...urls, lazy.url, ...library.urls]);
            """.trimIndent(),
            "prod/chunks/shared-ABCDEFGH.js" to """
                import { url } from './nested-QRSTUVWX.js';
                export const urls = [import.meta.url, url];
            """.trimIndent(),
            "prod/chunks/nested-QRSTUVWX.js" to "export const url = import.meta.url;",
            "prod/chunks/lazy-IJKLMNOP.js" to "export const url = import.meta.url;",
            "libraries/key/modules/widget.js" to """
                import { url } from '../chunks/library-ABCDEFGH.js';
                export const urls = [import.meta.url, url];
            """.trimIndent(),
            "libraries/key/chunks/library-ABCDEFGH.js" to "export const url = import.meta.url;",
        )
        val clientDir = if (packaged) null else directory.resolve("js").apply {
            scripts.filterKeys { it.startsWith("prod/") }.forEach { (path, body) ->
                resolve(path.removePrefix("prod/")).apply { parentFile.mkdirs() }.writeText(body)
            }
        }
        val resources = scripts.filterKeys { packaged || it.startsWith("libraries/") }
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
        val port = ServerSocket(0).use { it.localPort }
        var previousToken: String? = null
        for (version in listOf("first-deploy", "second-deploy")) {
            val requests = ConcurrentLinkedQueue<Pair<String, String?>>()
            val page = object : BmlPageRenderer, BmlBundledPage {
                override val route = "/"
                override val clientModule = "Page.js"
                override val assets = applicationAssets
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><head></head><body><pre id=\"result\">pending</pre></body></html>")
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
                val token = checkNotNull(Regex("_ts=([0-9a-f]+)").find(html)?.groupValues?.get(1)) { html }
                if (previousToken != null) assertNotEquals(previousToken, token, "A deployment changes tokens even with identical module bytes")
                val dom = dumpDom(chrome, directory, port, version)
                val result = checkNotNull(Regex("<pre id=\"result\">([^<]+)</pre>").find(dom)?.groupValues?.get(1)) { dom }
                val urls = Json.parseToJsonElement(result).jsonArray.map { URI(it.jsonPrimitive.content) }
                assertEquals(expectedPaths, urls.map { it.path }.toSet())
                urls.forEach { assertEquals("_ts=$token", it.rawQuery, it.toString()) }
                assertEquals(expectedPaths, requests.map { it.first }.toSet(), "Every module is downloaded after each deployment")
                requests.forEach { assertEquals(token, it.second, it.first) }
                requests.clear()
                dumpDom(chrome, directory, port, "$version-cached")
                assertTrue(requests.isEmpty(), "The retained browser cache reuses modules within a deployment: $requests")
                previousToken = token
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

    private fun dumpDom(chrome: File, directory: File, port: Int, version: String): String {
        val output = directory.resolve("$version.html")
        val errors = directory.resolve("$version.log")
        val process = ProcessBuilder(
            chrome.absolutePath, "--headless=new", "--disable-gpu", "--no-sandbox", "--no-first-run", "--no-default-browser-check",
            "--user-data-dir=${directory.resolve("browser-profile").absolutePath}", "--virtual-time-budget=5000", "--dump-dom",
            "http://localhost:$port/",
        ).redirectOutput(output).redirectError(errors).start()
        try {
            assertTrue(process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS), "Browser timed out: ${errors.readText()}")
            assertEquals(0, process.exitValue(), errors.readText())
            return output.readText()
        } finally {
            process.destroyForcibly()
        }
    }
}
