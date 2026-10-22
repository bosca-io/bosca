package bosca.design.catalog

import bosca.bml.library.BmlLibraryManifest
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
import java.util.jar.JarFile
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the catalog as an ordinary packaged consumer with no product backend. */
class CatalogApplicationTest {
    private val classpath = System.getProperty("catalog.classpath")

    @Test
    fun `packaged catalog serves isolated examples assets and real browser actions`() {
        val files = classpath.split(File.pathSeparator).map(::File)
        assertTrue(files.all { it.isFile && it.extension == "jar" })
        assertFalse(files.any { it.name.startsWith("git-") || it.name.startsWith("core-git") || it.name.startsWith("design-library-") })
        val catalog = files.single { it.name.startsWith("bml-design-catalog-") }
        val library = files.single { it.name.startsWith("bml-design-") && !it.name.startsWith("bml-design-catalog-") }
        val exports = JarFile(library).use { jar ->
            Json.decodeFromString(BmlLibraryManifest.serializer(), jar.getInputStream(jar.getJarEntry("META-INF/bml/library-manifest.json"))
                .bufferedReader().use { it.readText() }).components.filter { it.exported }
        }
        assertEquals(41, exports.size)
        val expectedApi = buildJsonObject {
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
        JarFile(catalog).use { jar ->
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("bosca/git/") })
            val manifest = jar.entries().asSequence().single { it.name.endsWith("/client-manifest.json") }
            assertTrue(jar.getInputStream(manifest).readBytes().isNotEmpty())
        }
        for (development in listOf(false, true)) withServer(development) { base, client ->
            for (path in listOf("/", "/foundation", "/themes", "/selection", "/source", "/json", "/comments", "/overlays", "/choices", "/views", "/trees", "/navigation", "/feedback", "/surfaces", "/components", "/components/controls",
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
            runBrowser(base, development, expectedApi, 60)
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
    fun `virtual JSON tables retain pending text and logical row positions`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--json-tables", 60)
        }
    }

    @Test
    fun `packaged catalog passes accessibility checks`() {
        for (development in listOf(false, true)) withServer(development) { base, _ ->
            runBrowser(base, development, "--accessibility", 120)
        }
    }

    @Test
    fun `catalog children bind distinct ports alongside occupied IPv4 and IPv6 endpoints`() {
        ServerSocketChannel.open(StandardProtocolFamily.INET).use { ipv4 ->
            ipv4.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            ServerSocketChannel.open(StandardProtocolFamily.INET6).use { ipv6 ->
                ipv6.bind(InetSocketAddress(InetAddress.getByName("::1"), 0))
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

    private fun runBrowser(base: String, development: Boolean, argument: String, timeoutSeconds: Long) {
        val log = File.createTempFile("catalog-browser-", ".log")
        val process = ProcessBuilder(System.getProperty("catalog.node"), System.getProperty("catalog.browser"), base,
            if (development) "development" else "production", argument).redirectErrorStream(true).redirectOutput(log).start()
        try {
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS), "Browser timed out: ${log.readText()}")
            assertEquals(0, process.exitValue(), log.readText())
        } finally { process.destroyForcibly(); log.delete() }
    }

    private fun withServer(development: Boolean, test: (String, HttpClient) -> Unit) {
        var base: String? = null
        val log = File.createTempFile("catalog-server-", ".log")
        val process = ProcessBuilder(System.getProperty("catalog.java"), "-Dbml.dev=$development", "-cp", classpath,
            "bosca.design.catalog.MainKt", "0").redirectErrorStream(true).redirectOutput(log).start()
        try {
            var boundPort: Int? = null
            for (attempt in 0 until 200) {
                assertTrue(process.isAlive, log.readText())
                boundPort = catalogBoundPort(log.readText())
                if (boundPort != null) break
                Thread.sleep(25)
            }
            val port = requireNotNull(boundPort) { "Catalog did not report a successful bind: ${log.readText()}" }
            val endpoint = "http://localhost:$port"
            base = endpoint
            val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
            val response = request(client, endpoint, "/")
            assertEquals(200, response.statusCode(), response.body())
            assertTrue("Bosca Design Library" in response.body(), response.body())
            assertTrue(process.isAlive, log.readText())
            test(endpoint, client)
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
        val builder = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(10))
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
