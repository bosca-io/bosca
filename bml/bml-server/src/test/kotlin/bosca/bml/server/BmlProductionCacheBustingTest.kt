package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.project.ContentDigest
import bosca.bml.library.BmlApplicationAssets
import bosca.bml.library.BmlAssetFile
import bosca.bml.library.BmlClientManifest
import bosca.bml.render.BmlBundledPage
import kotlinx.serialization.json.Json
import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.RenderContext
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BmlProductionCacheBustingTest {

    @Test
    fun `page using only the global script loads its content addressed asset without an import map`() {
        val page = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/plain")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, page.statusCode())
        assertFalse("type=\"importmap\"" in page.body(), page.body())
        assertTrue(Regex("src=\"/_bml/content/app-[a-f0-9]{64}\\.js\"").containsMatchIn(page.body()), page.body())
        assertFalse("src=\"/_bml/js/index.page.js" in page.body(), page.body())
    }

    @Test
    fun `development server does not traverse client assets for a production token`() {
        val clientDir = object : java.io.File("unused") {
            override fun isDirectory(): Boolean = true
            override fun listFiles(): Array<java.io.File> = error("development must not hash client assets")
        }

        BmlServer(
            project = CompiledProject("test", "1"),
            pages = emptyList(),
            clientDir = clientDir,
            dev = true,
        )
    }

    @Test
    fun `deployment token is stable and changes with generated render or bundled asset content`() {
        fun page(revision: String) = object : BmlPageRenderer {
            override val route = "/revision"
            override val renderRevision = revision
            override suspend fun render(ctx: RenderContext) = Unit
        }
        val assetDir = java.nio.file.Files.createTempDirectory("bml-deployment-token-test").toFile()
        try {
            val asset = assetDir.resolve("revision.page.js")
            asset.writeText("first")
            val project = CompiledProject("test", "1")
            val first = bmlDeploymentCacheToken(project, listOf(page("page-a")), emptyList(), null, null, assetDir)
            val same = bmlDeploymentCacheToken(project, listOf(page("page-a")), emptyList(), null, null, assetDir)
            val changedPage = bmlDeploymentCacheToken(
                project,
                listOf(page("page-b")),
                emptyList(),
                null,
                null,
                assetDir,
            )
            asset.writeText("second")
            val changedAsset = bmlDeploymentCacheToken(
                project,
                listOf(page("page-a")),
                emptyList(),
                null,
                null,
                assetDir,
            )

            assertEquals(first, same)
            assertNotEquals(first, changedPage)
            assertNotEquals(first, changedAsset)
        } finally {
            assetDir.deleteRecursively()
        }
    }

    @Test
    fun `deployment token includes the build id so code-only deploys change it`() {
        val project = CompiledProject("test", "0.0.1")
        val page = object : BmlPageRenderer {
            override val route = "/code"
            override suspend fun render(ctx: RenderContext) = Unit
        }
        fun token(buildId: String) = bmlDeploymentCacheToken(project, listOf(page), emptyList(), null, null, null, buildId)

        assertEquals(token("2026-09-24T10:00:00Z"), token("2026-09-24T10:00:00Z"))
        assertNotEquals(token("2026-09-24T10:00:00Z"), token("2026-09-24T11:00:00Z"))
    }

    @Test
    fun `build id is read from the resource the Gradle plugin writes`() {
        val resources = java.nio.file.Files.createTempDirectory("bml-build-id-test").toFile()
        try {
            resources.resolve(BML_BUILD_ID_RESOURCE).apply { parentFile.mkdirs() }.writeText("2026-09-24T10:00:00Z\n")
            java.net.URLClassLoader(arrayOf(resources.toURI().toURL()), null).use { loader ->
                assertEquals("2026-09-24T10:00:00Z", bmlBuildId(loader))
            }
            java.net.URLClassLoader(arrayOf(), null).use { empty -> assertEquals("", bmlBuildId(empty)) }
        } finally {
            resources.deleteRecursively()
        }
    }

    @Test
    fun `composed host identities affect deployment tokens independently of classpath order`() {
        val resources = java.nio.file.Files.createTempDirectory("bml-composed-build-ids").toFile()
        try {
            val embedded = resources.resolve("embedded/$BML_BUILD_ID_RESOURCE").apply {
                parentFile.mkdirs(); writeText("2026-10-20T10:00:00Z\n")
            }
            val host = resources.resolve("host/$BML_BUILD_ID_RESOURCE").apply {
                parentFile.mkdirs(); writeText("2026-10-21T10:00:00Z")
            }
            resources.resolve("blank/$BML_BUILD_ID_RESOURCE").apply { parentFile.mkdirs(); writeText(" \n") }
            fun identity(vararg names: String) = java.net.URLClassLoader(
                names.map { resources.resolve(it).toURI().toURL() }.toTypedArray(), null,
            ).use { bmlBuildId(it) }
            fun token(id: String) = bmlDeploymentCacheToken(CompiledProject("test", "1"), emptyList(), emptyList(), null, null, null, id)
            val first = identity("host", "embedded", "blank")
            assertEquals(embedded.readText().trim() + "\n" + host.readText(), first)
            assertEquals(first, identity("embedded", "host", "blank"))
            resources.resolve("merged/$BML_BUILD_ID_RESOURCE").apply {
                parentFile.mkdirs(); writeText("${host.readText()}\n${embedded.readText()}\n${host.readText()}")
            }
            assertEquals(first, identity("merged"))
            assertEquals(first, identity("merged", "embedded", "host"))
            host.writeText("2026-10-22T10:00:00Z")
            val changedHost = identity("embedded", "host")
            assertNotEquals(token(first), token(changedHost))
            embedded.writeText("2026-10-23T10:00:00Z")
            assertNotEquals(token(changedHost), token(identity("embedded", "host")))
            host.writeText(embedded.readText())
            assertEquals(embedded.readText(), identity("host", "embedded"))
            assertEquals("", identity("blank"))
        } finally {
            resources.deleteRecursively()
        }
    }

    @Test
    fun `production page links only content addressed assets without deployment queries or import maps`() {
        val html = client.send(HttpRequest.newBuilder(URI("http://localhost:$port/")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        assertFalse("importmap" in html, html)
        assertFalse("_ts=" in html, html)
        val urls = Regex("(?:href|src)=\"(/_bml/[^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(4, urls.size, html)
        for (url in urls) {
            val response = client.send(HttpRequest.newBuilder(URI("http://localhost:$port$url")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), url)
            assertEquals("public, max-age=31536000, immutable", response.headers().firstValue("Cache-Control").orElseThrow(), url)
        }
    }

    @Test
    fun `stable production asset URLs revalidate without a matching deployment token`() {
        for (path in listOf("/_bml/app.css", "/_bml/css/index.page.css", "/_bml/app.js")) {
            for (query in listOf("", "?_ts=old-deployment")) {
                val response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port$path$query")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, response.statusCode(), path + query)
                assertEquals(listOf("no-cache"), response.headers().allValues("Cache-Control"), path + query)
                val etag = response.headers().firstValue("ETag").orElseThrow()
                val conditional = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port$path$query"))
                        .header("If-None-Match", etag).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(304, conditional.statusCode(), path + query)
                assertEquals(listOf("no-cache"), conditional.headers().allValues("Cache-Control"), path + query)
            }
        }
    }

    @Test
    fun `library fonts and styles use immutable content URLs and validators match served bytes`() {
        fun get(path: String, etag: String? = null): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).apply {
                etag?.let { header("If-None-Match", it) }
            }.GET().build(), HttpResponse.BodyHandlers.ofString(),
        )
        val html = get("/plain").body()
        val cssUrl = checkNotNull(Regex("href=\"([^\"]+design-[^\"]+\\.css)\"").find(html)).groupValues[1]
        val css = get(cssUrl)
        assertEquals(200, css.statusCode())
        val fontUrl = checkNotNull(Regex("url\\(([^)]+)\\)").find(css.body())).groupValues[1]
        assertFalse("_ts=" in fontUrl)
        assertTrue(Regex("geist-[a-f0-9]{64}\\.woff2").containsMatchIn(fontUrl), fontUrl)
        val font = get(fontUrl)
        assertEquals(200, font.statusCode())
        assertEquals("public, max-age=31536000, immutable", font.headers().firstValue("Cache-Control").orElseThrow())
        assertEquals("no-cache", get("/_bml/libraries/design/geist.woff2").headers().firstValue("Cache-Control").orElseThrow())
        val etag = css.headers().firstValue("ETag").orElseThrow()
        assertEquals("\"${ContentDigest.sha256(css.body().toByteArray()).removePrefix("sha256:")}\"", etag)
        assertEquals(304, get(cssUrl, etag).statusCode())
    }

    @Test
    fun `global stylesheet dependencies resolve through immutable content URLs and plain URLs revalidate`() {
        fun get(path: String) = client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(java.time.Duration.ofSeconds(10)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val html = get("/").body()
        val cssUrl = checkNotNull(Regex("/_bml/content/app-[a-f0-9]{64}\\.css").find(html)).value
        val css = get(cssUrl).body()
        val fontUrl = checkNotNull(Regex("url\\(([^)]+)\\)").find(css)).groupValues[1]
        assertTrue(fontUrl.startsWith("/_bml/content/site-"), css)
        val font = get(fontUrl)
        assertEquals(200, font.statusCode())
        assertEquals("wOF2-site-font", font.body())
        assertEquals("public, max-age=31536000, immutable", font.headers().firstValue("Cache-Control").orElseThrow())
        val plain = get("/_bml/global/site.woff2")
        assertEquals(200, plain.statusCode())
        assertEquals("wOF2-site-font", plain.body())
        assertEquals("no-cache", plain.headers().firstValue("Cache-Control").orElseThrow())
        assertEquals(404, get("/_bml/global/missing.woff2").statusCode())
    }

    companion object {
        private var port = 0
        private lateinit var engine: NettyServerEngine
        private lateinit var clientDir: java.io.File
        private val client = HttpClient.newHttpClient()

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            clientDir = java.nio.file.Files.createTempDirectory("bml-production-cache-test").toFile()
                .also {
                    it.resolve("index.page-ABCDEFGH.js").writeText("console.log('page')")
                    it.resolve("bml-imports.json").writeText("""{"index.page.js":["index.page-ABCDEFGH.js","chunks/chunk-ABCDEFGH.js"]}""")
                    it.resolve("chunks/chunk-ABCDEFGH.js").apply { parentFile.mkdirs() }.writeText("console.log('chunk')")
                }
            val page = object : BmlPageRenderer {
                override val route = "/"
                override val clientModule = "Page.js"
                override val componentTags = listOf("badge")
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><head></head><body>production</body></html>")
                }
            }
            val resources = mapOf(
                "libraries/design/design.css" to "@font-face{font-family:Geist;src:url(/_bml/libraries/design/geist.woff2)}".toByteArray(),
                "libraries/design/geist.woff2" to "wOF2-test-font".toByteArray(),
                "global/site.woff2" to "wOF2-site-font".toByteArray(),
            )
            val manifest = Json.encodeToString(BmlClientManifest.serializer(), BmlClientManifest(
                files = resources.map { (path, bytes) -> BmlAssetFile(path, ContentDigest.sha256(bytes)) },
            )).toByteArray()
            val plainPage = object : BmlPageRenderer, BmlBundledPage {
                override val assets = BmlApplicationAssets("test", mapOf("/plain" to listOf("/_bml/libraries/design/design.css")), emptyMap()) {
                    if (it == "test/client-manifest.json") manifest else resources[it.removePrefix("test/")]
                }
                override val route = "/plain"
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><head></head><body>plain</body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject("test", "1"),
                pages = listOf(page, plainPage),
                components = listOf(
                    BmlComponentInfo("badge", "badge", ".badge{}", emptyList(), clientModule = "Badge.js"),
                ),
                globalCss = "body{} @font-face{font-family:Site;src:url(/_bml/global/site.woff2)}",
                globalJs = "console.log('app')",
                clientDir = clientDir,
                port = port,
                dev = false,
            )
            val app = BoscaApplication(
                ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n".byteInputStream()),
            )
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-production-cache-test-server") { engine.start() }
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).close()
                    return
                } catch (_: Exception) {
                    Thread.sleep(50)
                }
            }
            error("server did not start on $port")
        }

        @JvmStatic
        @AfterClass
        fun shutdown() {
            if (::engine.isInitialized) engine.stopWithoutHalting()
            clientDir.deleteRecursively()
        }
    }
}
