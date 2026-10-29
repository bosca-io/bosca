package bosca.design.catalog

import bosca.bml.library.BmlLibraryManifest
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.ServerSocketChannel
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the catalog as an ordinary packaged consumer with no product backend. */
@Execution(ExecutionMode.CONCURRENT)
class CatalogApplicationTest {
    private val classpath = System.getProperty("catalog.classpath")

    @Test
    fun `file drops retain native selection and disabled behavior across palettes`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--file-drop", 60)
        }
    }

    @Test
    fun `driver diagnostics distinguish pending promises and blocked renderers`() {
        withServer(false) { base, _ ->
            runBrowser(base, false, "--driver-promise-probe", 60)
            runBrowser(base, false, "--driver-busy-probe", 60)
        }
    }

    @Test
    fun `deep trees expose all levels and retain keyboard navigation`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--deep-tree", 60)
        }
    }

    @Test
    fun `Firefox trees retain native keyboard navigation`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--firefox-tree", 60, System.getProperty("catalog.firefoxBrowser"))
        }
    }

    @Test
    fun `surface keyboard scrolling completes before retained layout checks`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--surfaces", 60)
        }
    }

    @Test
    fun `feedback tooltips finish placement and rendering checks`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--feedback", 60)
        }
    }

    @Test
    fun `dashboard layouts retain widgets and native form values`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--dashboards", 90)
        }
    }

    @Test
    fun `native executable serves production resources and interactions without a workspace`() {
        val binary = File(System.getProperty("catalog.binary"))
        assertTrue(binary.isFile && binary.canExecute(), binary.path)
        val directory = kotlin.io.path.createTempDirectory("native catalog-").toFile()
        try {
            val executable = directory.resolve(binary.name)
            java.nio.file.Files.copy(binary.toPath(), executable.toPath(), java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            assertEquals(listOf(binary.name), directory.listFiles()?.map { it.name })
            withServer(false, executable) { base, _ ->
                runBrowser(base, false, "--resources", 60)
                runBrowser(base, false, "--visualizations", 90)
                runBrowser(base, false, expectedComponentApi(), 180)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `cold pages download only their optional engines and styles`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--resources", 60)
        }
    }

    @Test
    fun `visualization engines stay isolated to consuming pages`() {
        for (development in listOf(false, true)) withServer(development) { base, client ->
            for (path in listOf("/foundation", "/flows", "/charts", "/components/visualization")) {
                val response = request(client, base, path)
                assertEquals(200, response.statusCode(), path)
                val html = response.body()
                val scripts = Regex("""<script\b[^>]*\bsrc="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
                assertTrue(scripts.isNotEmpty(), path)
                val modules = linkedMapOf<URI, String>()
                fun visit(uri: URI) {
                    if (uri in modules) return
                    val asset = request(client, base, uri.toString())
                    assertEquals(200, asset.statusCode(), uri.toString())
                    val body = asset.body(); modules[uri] = body
                    Regex("""(?:import|from)\s*["']([^"']+)["']""").findAll(body).forEach { match ->
                        if (match.groupValues[1].startsWith(".")) visit(uri.resolve(match.groupValues[1]))
                    }
                }
                scripts.forEach { visit(URI(base).resolve(it.replace("&amp;", "&"))) }
                val source = modules.values.joinToString("\n")
                assertEquals(path in listOf("/flows", "/components/visualization"), "data-flow-ready" in source, "$path VueFlow adapter")
                assertEquals(path in listOf("/charts", "/components/visualization"), "data-chart-ready" in source, "$path Chart.js adapter")
                assertEquals(path in listOf("/flows", "/components/visualization"), "vue-flow__node" in source, "$path VueFlow engine")
                assertEquals(path in listOf("/charts", "/components/visualization"), "chartjs" in source, "$path Chart.js engine")
                if (path == "/charts") {
                    for (palette in listOf("Dark", "Light"))
                        assertTrue(Regex("""<caption\b[^>]*>$palette palette visits</caption>""").containsMatchIn(html), html)
                    assertTrue("Visits" in html && "Mon" in html && "Wed" in html, html)
                }
            }
        }
    }

    @Test
    fun `flows and charts retain independent state and native values`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--visualizations", 90)
        }
    }

    @Test
    fun `rich documents retain native form values formatting and independent drafts`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--documents", 60)
        }
    }

    @Test
    fun `shell and profile examples use independent local actions`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--shell", 60)
        }
    }

    @Test
    fun `workspaces fill the catalog page and retain source preview and appearance`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--workspaces", 60)
        }
    }

    @Test
    fun `source languages authoring colors and compact controls follow consumer styles`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--source-polish", 60)
        }
    }

    @Test
    fun `catalog appearance persists across navigation reload and history`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--appearance", 60)
        }
    }

    @Test
    fun `packaged catalog serves isolated examples assets and real browser actions`() {
        val files = classpath.split(File.pathSeparator).map(::File)
        assertTrue(files.all { it.isFile && it.extension == "jar" })
        assertFalse(files.any { it.name.startsWith("git-") || it.name.startsWith("core-git") || it.name.startsWith("design-library-") })
        val catalog = files.single { it.name.startsWith("bml-design-catalog-") }
        val expectedApi = expectedComponentApi()
        JarFile(catalog).use { jar ->
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("bosca/git/") })
            val manifest = jar.entries().asSequence().single { it.name.endsWith("/client-manifest.json") }
            assertTrue(jar.getInputStream(manifest).readBytes().isNotEmpty())
        }
        for (development in listOf(false, true)) withServer(development) { base, client ->
            for (path in listOf("/", "/foundation", "/themes", "/selection", "/source", "/source/languages", "/workspaces", "/documents", "/flows", "/charts", "/dashboards", "/components/visualization", "/json", "/comments", "/overlays", "/choices", "/views", "/trees", "/navigation", "/feedback", "/surfaces", "/components", "/components/controls",
                "/components/layout", "/components/feedback", "/components/overlays", "/components/content", "/components/aliases")) {
                val response = request(client, base, path)
                assertEquals(200, response.statusCode(), path + response.body())
                assertTrue("Bosca Design Library" in response.body(), response.body())
                assertFalse("/_git/" in response.body() || "bosca.git" in response.body())
            }
            val foundation = request(client, base, "/foundation").body()
            val form = requireNotNull(Regex("<form[^>]+>").find(foundation)).value
            val key = requireNotNull(Regex("data-bml-state-key=\"([^\"]+)\"").find(form)).groupValues[1]
            fun save(name: String): String {
                val body = buildJsonObject {
                    put("page", "/foundation")
                    put("path", "/foundation")
                    put("stateKey", key)
                    put("method", "save")
                    put("args", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(name)) })
                }.toString()
                val response = request(client, base, "/_bml/action", body)
                assertEquals(200, response.statusCode(), response.body())
                return response.body()
            }
            assertTrue("Use at least three characters." in save("x"))
            assertTrue("Saved Catalog test." in save("Catalog test"))
            fun search(body: String) = request(client, base, "/_bml/contract/CatalogChoices/search", body)
            val first = search("""[{"query":"layout","offset":0}]""")
            assertEquals(200, first.statusCode(), first.body())
            assertEquals(3, Json.decodeFromString(CatalogChoicePage.serializer(), first.body()).nextOffset)
            val last = search("""[{"query":"layout","offset":3}]""")
            assertEquals(200, last.statusCode(), last.body())
            assertEquals(listOf("page-heading", "section"), Json.decodeFromString(CatalogChoicePage.serializer(), last.body()).options.map { it.value })
            assertEquals(500, search("""[{"query":"layout","offset":0,"fail":true}]""").statusCode())
            assertEquals(200, search("""[{"query":"layout","offset":0}]""").statusCode())
            val links = Regex("<link[^>]+href=\"([^\"]+)\"").findAll(foundation)
                .map { it.groupValues[1].replace("&amp;", "&") }
                .filter { it.startsWith("/_bml/libraries/") && it.substringBefore('?').endsWith(".css") }.toList()
            val styles = links.map { request(client, base, it).also { response -> assertEquals(200, response.statusCode()) }.body() }
            val fonts = Regex("""url\(["']?(/_bml/libraries/[^)"']+\.woff2)["']?\)""")
                .findAll(styles.joinToString("\n")).map { it.groupValues[1] }.toList()
            assertEquals(11, fonts.size)
            assertEquals(fonts.distinct(), fonts)
            for (url in fonts) {
                val response = client.send(HttpRequest.newBuilder(URI(base + url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                assertEquals(200, response.statusCode(), url)
                assertEquals("font/woff2", response.headers().firstValue("Content-Type").orElse(""))
                assertEquals("wOF2", response.body().take(4).toByteArray().decodeToString())
            }
            runBrowser(base, development, expectedApi, 180)
        }
    }

    @Test
    fun `copy confirmation does not add an outer scrollbar`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--copy-overflow", 60)
        }
    }

    @Test
    fun `control borders follow consumer palettes and preserve divider colors`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--control-borders", 60)
        }
    }

    @Test
    fun `local view focus survives delayed action completion`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--tab-focus", 60)
        }
    }

    @Test
    fun `source search controls fit normal and compact viewers`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--source-panel", 60)
        }
    }

    @Test
    fun `source search highlights retain readable text across palettes`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--source-search", 60)
        }
    }

    @Test
    fun `empty lists do not expose inaccessible widgets`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--empty-widgets", 60)
        }
    }

    @Test
    fun `virtual JSON tables retain pending text and logical row positions`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--json-tables", 60)
        }
    }

    @Test
    fun `selection names retain native keyboard removal and focus`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--choices", 60)
        }
    }

    @Test
    fun `contrast findings retain readable colors when scrolled into view`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--contrast-visibility", 60)
        }
    }

    @Test
    fun `packaged catalog passes accessibility checks at desktop width`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--accessibility-desktop", 120)
        }
    }

    @Test
    fun `packaged catalog passes accessibility checks at phone width`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--accessibility-phone", 120)
        }
    }

    @Test
    fun `catalog JVM fixtures never accept another IPv4 listener as their server`() {
        val requests = AtomicInteger()
        val listener = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        listener.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.use {
                val body = "Bosca Design Library".toByteArray()
                it.sendResponseHeaders(200, body.size.toLong())
                it.responseBody.write(body)
            }
        }
        listener.start()
        try {
            val port = listener.address.port
            for (development in listOf(false, true)) {
                try {
                    withServer(development, requestedPort = port) { base, client ->
                        assertEquals("[::1]", URI(base).host)
                        val response = request(client, base, "/components")
                        assertEquals(200, response.statusCode(), response.body())
                        assertTrue("Bosca Design Library" in response.body(), response.body())
                    }
                } catch (failure: AssertionError) {
                    // Some hosts reject wildcard/specific-address port sharing at bind time.
                    assertTrue(failure.suppressed.any { it.message.orEmpty().contains("could not bind port $port") }, failure.toString())
                }
            }
            assertEquals(0, requests.get(), "Readiness must never probe the other listener")
        } finally {
            listener.stop(0)
        }
    }

    @Test
    fun `catalog children bind distinct ports alongside occupied IPv4 and IPv6 endpoints`() {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { ipv4 ->
            ipv4.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            ServerSocketChannel.open(StandardProtocolFamily.INET6).use { ipv6 ->
                ipv6.bind(InetSocketAddress(InetAddress.getByName("::1"), (ipv4.localAddress as InetSocketAddress).port))
                val occupied = listOf(ipv4, ipv6).map { (it.localAddress as InetSocketAddress).port }
                withServer(false) { production, productionClient ->
                    withServer(true) { development, developmentClient ->
                        assertFalse(URI(production).port in occupied)
                        assertFalse(URI(development).port in occupied)
                        assertFalse(production == development)
                        for ((base, client) in listOf(production to productionClient, development to developmentClient)) {
                            val response = request(client, base, "/components")
                            assertEquals(200, response.statusCode(), response.body())
                            assertTrue("Bosca Design Library" in response.body(), response.body())
                        }
                    }
                }
            }
        }
    }

    private fun expectedComponentApi(): String {
        val library = classpath.split(File.pathSeparator).map(::File).single { it.name.startsWith("bml-design-") && !it.name.startsWith("bml-design-catalog-") }
        val exports = JarFile(library).use { jar ->
            Json.decodeFromString(BmlLibraryManifest.serializer(), jar.getInputStream(jar.getJarEntry("META-INF/bml/library-manifest.json"))
                .bufferedReader().use { it.readText() }).components.filter { it.exported }
        }
        assertTrue(exports.isNotEmpty())
        return buildJsonObject {
            for (component in exports) put(component.tag, buildJsonObject {
                put("scoped", component.styles.isNotBlank())
                put("props", buildJsonArray {
                    for (prop in component.props) add(buildJsonObject {
                        put("name", prop.name)
                        put("type", prop.declaredType)
                        put("hasDefault", prop.hasDefault)
                    })
                })
            })
        }.toString()
    }

    private fun runBrowser(base: String, development: Boolean, argument: String, timeoutSeconds: Long,
                           script: String = System.getProperty("catalog.browser")) {
        val log = File.createTempFile("catalog-browser-", ".log")
        val mode = (if (System.getProperty("catalog.binary") == null) "" else "native-") +
            (if (development) "development" else "production") +
            if (argument in setOf("--choices", "--feedback", "--surfaces")) "-focused" else ""
        val process = ProcessBuilder(System.getProperty("catalog.node"), script, base,
            mode, argument).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "Browser timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.toList().asReversed().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
            log.delete()
        }
    }

    private fun withServer(development: Boolean, executable: File? = null, requestedPort: Int = 0, test: (String, HttpClient) -> Unit) {
        var base: String? = null
        val log = File.createTempFile("catalog-server-", ".log")
        val command = if (executable == null) listOf(System.getProperty("catalog.java"),
            "-Djava.net.preferIPv4Stack=false", "-Djava.net.preferIPv6Addresses=true",
            "-Dbml.dev=$development", "-cp", classpath, "bosca.design.catalog.MainKt", requestedPort.toString())
        else listOf(executable.absolutePath, requestedPort.toString())
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log)
        if (executable != null) {
            builder.directory(executable.parentFile)
            builder.environment()["BML_DEV"] = "false"
        }
        val process = builder.start()
        try {
            var boundPort: Int? = null
            for (attempt in 0 until 200) {
                assertTrue(process.isAlive, log.readText())
                boundPort = catalogBoundPort(log.readText())
                if (boundPort != null) break
                Thread.sleep(25)
            }
            val port = requireNotNull(boundPort) { "Catalog did not report a successful bind: ${log.readText()}" }
            val host = if (executable == null) "[::1]" else "localhost"
            val endpoint = "http://$host:$port"
            base = endpoint
            // Match Chromium's HTTP/1.1 transport on the loopback fixture origin.
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build().use { client ->
                    val response = request(client, endpoint, "/")
                    assertEquals(200, response.statusCode(), response.body())
                    assertTrue("Bosca Design Library" in response.body(), response.body())
                    assertTrue(process.isAlive, log.readText())
                    test(endpoint, client)
                }
        } catch (failure: Throwable) {
            val state = if (process.isAlive) "running" else "exited ${process.exitValue()}"
            failure.addSuppressed(IllegalStateException("Catalog development=$development at ${base ?: "unbound port"} ($state): ${log.readText()}"))
            throw failure
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            log.delete()
        }
    }

    private fun request(client: HttpClient, base: String, path: String, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(base).resolve(path)).timeout(Duration.ofSeconds(10))
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        return try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (failure: java.net.http.HttpTimeoutException) {
            throw AssertionError("Timed out requesting $path from $base", failure)
        } catch (failure: java.io.IOException) {
            throw AssertionError("Failed requesting $path from $base", failure)
        }
    }
}
