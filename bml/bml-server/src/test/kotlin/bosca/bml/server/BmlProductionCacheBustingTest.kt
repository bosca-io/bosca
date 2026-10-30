package bosca.bml.server

import bosca.bml.project.CompiledProject
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
    fun `page using only the global script retains the deployment import map`() {
        val page = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/plain")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, page.statusCode())
        assertTrue("type=\"importmap\"" in page.body(), page.body())
        assertTrue("src=\"/_bml/app.js?_ts=" in page.body(), page.body())
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
    fun `production page gives every generated css and js url one deployment token`() {
        val html = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val tokens = Regex("(?:href|src)=\"/_bml/(?:app\\.(?:css|js)|(?:css|js)/index\\.page\\.(?:css|js))\\?_ts=([0-9a-f]+)")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(4, tokens.size, html)
        assertEquals(1, tokens.distinct().size, html)
    }

    @Test
    fun `production css and js use long-lived immutable caching with the deployment token`() {
        val html = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val token = Regex("_ts=([0-9a-f]+)").find(html)?.groupValues?.get(1)
        checkNotNull(token) { html }
        for (path in listOf(
            "/_bml/app.css",
            "/_bml/css/index.page.css",
            "/_bml/app.js",
            "/_bml/js/index.page.js",
            "/_bml/js/chunks/chunk-ABCDEFGH.js",
        )) {
            val response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port$path?_ts=$token")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode(), path)
            assertEquals(
                "public, max-age=31536000, immutable",
                response.headers().firstValue("Cache-Control").orElse(null),
                path,
            )
        }
    }

    @Test
    fun `stable production asset URLs revalidate without a matching deployment token`() {
        for (path in listOf("/_bml/app.css", "/_bml/css/index.page.css", "/_bml/app.js", "/_bml/js/index.page.js", "/_bml/js/chunks/chunk-ABCDEFGH.js")) {
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
    fun `production import map adds the deployment token to filesystem chunks`() {
        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        val html = response.body()
        val token = checkNotNull(Regex("_ts=([0-9a-f]+)").find(html)?.groupValues?.get(1)) { html }
        assertTrue(html.contains("\"/_bml/js/chunks/chunk-ABCDEFGH.js\":\"/_bml/js/chunks/chunk-ABCDEFGH.js?_ts=$token\""), html)
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
                    it.resolve("index.page.js").writeText("console.log('page')")
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
            val plainPage = object : BmlPageRenderer {
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
                globalCss = "body{}",
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
