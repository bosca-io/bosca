package bosca.server.routing

import bosca.server.ContentType
import bosca.server.BoscaApplication
import bosca.server.HttpHeaders
import bosca.server.HttpMethod
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTest {

    @Test
    fun `static content types cover supported extensions and fallback`() {
        val expected = mapOf(
            "index.html" to ContentType.Text.Html,
            "INDEX.HTM" to ContentType.Text.Html,
            "site.css" to ContentType.Text.Css,
            "app.js" to ContentType.Text.JavaScript,
            "app.mjs" to ContentType.Text.JavaScript,
            "data.json" to ContentType.Application.Json,
            "document.xml" to ContentType.Application.Xml,
            "readme.txt" to ContentType.Text.Plain,
            "image.svg" to ContentType("image", "svg+xml"),
            "image.png" to ContentType.Image.Png,
            "image.jpg" to ContentType.Image.Jpeg,
            "image.jpeg" to ContentType.Image.Jpeg,
            "image.gif" to ContentType.Image.Gif,
            "favicon.ico" to ContentType("image", "x-icon"),
            "font.woff" to ContentType("font", "woff"),
            "font.woff2" to ContentType("font", "woff2"),
            "font.ttf" to ContentType("font", "ttf"),
            "font.otf" to ContentType("font", "otf"),
            "font.eot" to ContentType("application", "vnd.ms-fontobject"),
            "image.webp" to ContentType("image", "webp"),
            "image.avif" to ContentType("image", "avif"),
            "video.mp4" to ContentType("video", "mp4"),
            "video.webm" to ContentType("video", "webm"),
            "document.pdf" to ContentType("application", "pdf"),
            "bundle.map" to ContentType.Application.Json,
            "archive.bin" to ContentType.Application.OctetStream,
            "extensionless" to ContentType.Application.OctetStream,
        )

        expected.forEach { (path, contentType) ->
            assertEquals(contentType, contentTypeForPath(path), path)
        }
    }

    // --- Basic HTTP method routing ---

    @Test
    fun `GET route resolves correctly`() = runTest {
        val router = Router()
        router.get("/hello") {}
        val resolved = router.resolve(HttpMethod.Get, "/hello")
        assertNotNull(resolved)
    }

    @Test
    fun `POST route resolves correctly`() = runTest {
        val router = Router()
        router.post("/submit") {}
        val resolved = router.resolve(HttpMethod.Post, "/submit")
        assertNotNull(resolved)
    }

    @Test
    fun `PUT route resolves correctly`() = runTest {
        val router = Router()
        router.put("/update") {}
        val resolved = router.resolve(HttpMethod.Put, "/update")
        assertNotNull(resolved)
    }

    @Test
    fun `DELETE route resolves correctly`() = runTest {
        val router = Router()
        router.delete("/remove") {}
        val resolved = router.resolve(HttpMethod.Delete, "/remove")
        assertNotNull(resolved)
    }

    @Test
    fun `PATCH route resolves correctly`() = runTest {
        val router = Router()
        router.patch("/modify") {}
        val resolved = router.resolve(HttpMethod.Patch, "/modify")
        assertNotNull(resolved)
    }

    // --- Root path routing ---

    @Test
    fun `GET root path resolves`() = runTest {
        val router = Router()
        router.get("/") {}
        val resolved = router.resolve(HttpMethod.Get, "/")
        assertNotNull(resolved)
    }

    @Test
    fun `POST root path resolves`() = runTest {
        val router = Router()
        router.post("/") {}
        val resolved = router.resolve(HttpMethod.Post, "/")
        assertNotNull(resolved)
    }

    @Test
    fun `root path does not match non-root requests`() = runTest {
        val router = Router()
        router.get("/") {}
        assertNull(router.resolve(HttpMethod.Get, "/other"))
    }

    @Test
    fun `nested route under root prefix resolves`() = runTest {
        val router = Router()
        router.route("/") {
            get("/users") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/users")
        assertNotNull(resolved)
    }

    // --- Unmatched and wrong method ---

    @Test
    fun `returns null for unmatched path`() = runTest {
        val router = Router()
        router.get("/hello") {}
        val resolved = router.resolve(HttpMethod.Get, "/goodbye")
        assertNull(resolved)
    }

    @Test
    fun `returns null for wrong method`() = runTest {
        val router = Router()
        router.get("/hello") {}
        val resolved = router.resolve(HttpMethod.Post, "/hello")
        assertNull(resolved)
    }

    @Test
    fun `HEAD resolves explicit routes before falling back to GET`() = runTest {
        val router = Router()
        router.get("/resource") {}
        router.head("/resource") {}
        router.get("/fallback") {}

        assertEquals(HttpMethod.Head, router.resolve(HttpMethod.Head, "/resource")?.let { HttpMethod.Head })
        assertEquals("/resource", router.resolve(HttpMethod.Head, "/resource")?.routePattern)
        assertEquals("/fallback", router.resolve(HttpMethod.Head, "/fallback")?.routePattern)
        assertNull(router.resolve(HttpMethod.Head, "/missing"))
    }

    @Test
    fun `explicit HEAD route in a later child router wins over an earlier GET fallback`() = runTest {
        val router = Router()
        router.authenticate("jwt", optional = true) {
            get("/documents/{id}/state") {}
            get("/documents/{id}/other") {}
        }
        router.authenticate("jwt") {
            head("/documents/{id}/state") {}
        }

        val head = requireNotNull(router.resolve(HttpMethod.Head, "/documents/7/state"))
        assertEquals(false, head.authConfig?.optional)
        assertEquals("7", head.pathParameters["id"])
        assertEquals(true, router.resolve(HttpMethod.Get, "/documents/7/state")?.authConfig?.optional)
        assertEquals(true, router.resolve(HttpMethod.Head, "/documents/7/other")?.authConfig?.optional)
    }

    @Test
    fun `HEAD fallback resolves parameterized GET routes`() = runTest {
        val router = Router()
        router.get("/users/{id}") {}

        val resolved = requireNotNull(router.resolve(HttpMethod.Head, "/users/42"))

        assertEquals("42", resolved.pathParameters["id"])
        assertEquals("/users/{id}", resolved.routePattern)
    }

    @Test
    fun `allowed methods include direct parameterized and nested routes`() {
        val router = Router()
        router.get("/items/{id}") {}
        router.post("/items/{id}") {}
        router.route("/api/{version}") {
            put("/items/{id}") {}
        }
        router.authenticate("jwt") {
            delete("/protected/{id}") {}
        }

        assertEquals(setOf(HttpMethod.Get, HttpMethod.Post), router.allowedMethods("/items/one"))
        assertEquals(setOf(HttpMethod.Put), router.allowedMethods("/api/v1/items/one"))
        assertEquals(setOf(HttpMethod.Delete), router.allowedMethods("/protected/one"))
        assertTrue(router.allowedMethods("/missing").isEmpty())
    }

    @Test
    fun `returns null for completely empty router`() = runTest {
        val router = Router()
        assertNull(router.resolve(HttpMethod.Get, "/anything"))
    }

    // --- Path parameters ---

    @Test
    fun `path parameter extraction`() = runTest {
        val router = Router()
        router.get("/users/{id}") {}
        val resolved = router.resolve(HttpMethod.Get, "/users/123")
        assertNotNull(resolved)
        assertEquals("123", resolved.pathParameters["id"])
    }

    @Test
    fun `multiple path parameters`() = runTest {
        val router = Router()
        router.get("/users/{userId}/posts/{postId}") {}
        val resolved = router.resolve(HttpMethod.Get, "/users/42/posts/99")
        assertNotNull(resolved)
        assertEquals("42", resolved.pathParameters["userId"])
        assertEquals("99", resolved.pathParameters["postId"])
    }

    @Test
    fun `path parameter with special characters`() = runTest {
        val router = Router()
        router.get("/items/{id}") {}
        val resolved = router.resolve(HttpMethod.Get, "/items/abc-def_123")
        assertNotNull(resolved)
        assertEquals("abc-def_123", resolved.pathParameters["id"])
    }

    @Test
    fun `path parameter does not match extra segments`() = runTest {
        val router = Router()
        router.get("/users/{id}") {}
        assertNull(router.resolve(HttpMethod.Get, "/users/123/extra"))
    }

    @Test
    fun `path parameter does not match fewer segments`() = runTest {
        val router = Router()
        router.get("/users/{id}/posts") {}
        assertNull(router.resolve(HttpMethod.Get, "/users/123"))
    }

    // --- Nested routes ---

    @Test
    fun `nested routes via route`() = runTest {
        val router = Router()
        router.route("/api") {
            get("/users") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/api/users")
        assertNotNull(resolved)
    }

    @Test
    fun `deeply nested routes`() = runTest {
        val router = Router()
        router.route("/api") {
            route("/v1") {
                get("/users") {}
            }
        }
        val resolved = router.resolve(HttpMethod.Get, "/api/v1/users")
        assertNotNull(resolved)
    }

    @Test
    fun `nested route with path parameter`() = runTest {
        val router = Router()
        router.route("/users") {
            get("/{id}") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/users/456")
        assertNotNull(resolved)
        assertEquals("456", resolved.pathParameters["id"])
    }

    @Test
    fun `nested route prefix parameter merges with child parameters`() = runTest {
        val router = Router()
        router.route("/orgs/{orgId}") {
            get("/members/{memberId}") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/orgs/abc/members/xyz")
        assertNotNull(resolved)
        assertEquals("abc", resolved.pathParameters["orgId"])
        assertEquals("xyz", resolved.pathParameters["memberId"])
    }

    @Test
    fun `nested route does not match parent prefix alone`() = runTest {
        val router = Router()
        router.route("/api") {
            get("/users") {}
        }
        assertNull(router.resolve(HttpMethod.Get, "/api"))
    }

    @Test
    fun `sibling nested routes resolve independently`() = runTest {
        val router = Router()
        router.route("/api") {
            get("/users") {}
            get("/posts") {}
        }
        assertNotNull(router.resolve(HttpMethod.Get, "/api/users"))
        assertNotNull(router.resolve(HttpMethod.Get, "/api/posts"))
        assertNull(router.resolve(HttpMethod.Get, "/api/comments"))
    }

    // --- Authentication ---

    @Test
    fun `authenticate wraps routes with auth config`() = runTest {
        val router = Router()
        router.authenticate("jwt") {
            get("/protected") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/protected")
        assertNotNull(resolved)
        assertNotNull(resolved.authConfig)
        assertTrue(resolved.authConfig!!.providers.contains("jwt"))
    }

    @Test
    fun `authenticate with optional flag`() = runTest {
        val router = Router()
        router.authenticate("jwt", optional = true) {
            get("/maybe-protected") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/maybe-protected")
        assertNotNull(resolved)
        assertNotNull(resolved.authConfig)
        assertTrue(resolved.authConfig!!.optional)
    }

    @Test
    fun `authenticate with multiple providers`() = runTest {
        val router = Router()
        router.authenticate("jwt", "basic") {
            get("/multi-auth") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/multi-auth")
        assertNotNull(resolved)
        assertNotNull(resolved.authConfig)
        assertEquals(listOf("jwt", "basic"), resolved.authConfig!!.providers)
    }

    @Test
    fun `unauthenticated route has no auth config`() = runTest {
        val router = Router()
        router.get("/public") {}
        val resolved = router.resolve(HttpMethod.Get, "/public")
        assertNotNull(resolved)
        assertNull(resolved.authConfig)
    }

    @Test
    fun `authenticate nested under route prefix`() = runTest {
        val router = Router()
        router.route("/api") {
            authenticate("jwt") {
                get("/secret") {}
            }
        }
        val resolved = router.resolve(HttpMethod.Get, "/api/secret")
        assertNotNull(resolved)
        assertNotNull(resolved.authConfig)
        assertTrue(resolved.authConfig!!.providers.contains("jwt"))
    }

    // --- WebSocket ---

    @Test
    fun `webSocket route resolves`() {
        val router = Router()
        router.webSocket("/ws") {}
        val result = router.resolveWebSocket("/ws")
        assertNotNull(result)
    }

    @Test
    fun `webSocket route with protocol`() {
        val router = Router()
        router.webSocket("/ws", protocol = "graphql-ws") {}
        val result = router.resolveWebSocket("/ws")
        assertNotNull(result)
        assertEquals("graphql-ws", result.protocol)
    }

    @Test
    fun `webSocket unmatched path returns null`() {
        val router = Router()
        router.webSocket("/ws") {}
        assertNull(router.resolveWebSocket("/other"))
    }

    @Test
    fun `webSocket route within nested scope`() {
        val router = Router()
        router.route("/api") {
            webSocket("/ws") {}
        }
        val result = router.resolveWebSocket("/api/ws")
        assertNotNull(result)
    }

    // --- SSE ---

    @Test
    fun `SSE route resolves`() {
        val router = Router()
        router.sse("/events") {}
        val result = router.resolveSSE("/events")
        assertNotNull(result)
    }

    @Test
    fun `SSE unmatched path returns null`() {
        val router = Router()
        router.sse("/events") {}
        assertNull(router.resolveSSE("/other"))
    }

    @Test
    fun `SSE route within nested scope`() {
        val router = Router()
        router.route("/api") {
            sse("/events") {}
        }
        val result = router.resolveSSE("/api/events")
        assertNotNull(result)
    }

    // --- Static file serving ---

    @Test
    fun `staticFiles resolves existing file`() = runTest {
        val dir = createTempDirWithFile("hello.txt", "Hello World")
        val router = Router()
        router.staticFiles("/static", dir)
        val resolved = router.resolve(HttpMethod.Get, "/static/hello.txt")
        assertNotNull(resolved)
    }

    @Test
    fun `staticFiles returns null for nonexistent file`() = runTest {
        val dir = createTempDirWithFile("hello.txt", "Hello World")
        val router = Router()
        router.staticFiles("/static", dir)
        assertNull(router.resolve(HttpMethod.Get, "/static/missing.txt"))
    }

    @Test
    fun `staticFiles blocks path traversal`() = runTest {
        val dir = createTempDirWithFile("hello.txt", "Hello World")
        val router = Router()
        router.staticFiles("/static", dir)
        assertNull(router.resolve(HttpMethod.Get, "/static/../etc/passwd"))
    }

    @Test
    fun `staticFiles serves index html for root path`() = runTest {
        val dir = createTempDirWithFile("index.html", "<html></html>")
        val router = Router()
        router.staticFiles("/", dir)
        val resolved = router.resolve(HttpMethod.Get, "/")
        assertNotNull(resolved)
    }

    @Test
    fun `staticFiles root returns null without index html`() = runTest {
        val dir = createTempDirWithFile("other.txt", "content")
        val router = Router()
        router.staticFiles("/", dir)
        assertNull(router.resolve(HttpMethod.Get, "/"))
    }

    @Test
    fun `staticFiles only matches GET requests`() = runTest {
        val dir = createTempDirWithFile("hello.txt", "Hello World")
        val router = Router()
        router.staticFiles("/static", dir)
        assertNull(router.resolve(HttpMethod.Post, "/static/hello.txt"))
    }

    @Test
    fun `staticResources resolves classpath resource`() = runTest {
        val router = Router()
        router.staticResources("/assets", "test-static")
        val resolved = router.resolve(HttpMethod.Get, "/assets/test.txt")
        assertNotNull(resolved)
    }

    @Test
    fun `staticResources returns null for missing resource`() = runTest {
        val router = Router()
        router.staticResources("/assets", "test-static")
        assertNull(router.resolve(HttpMethod.Get, "/assets/nonexistent.txt"))
    }

    @Test
    fun `staticResources serves index html for root`() = runTest {
        val router = Router()
        router.staticResources("/", "test-static-with-index")
        val resolved = router.resolve(HttpMethod.Get, "/")
        assertNotNull(resolved)
    }

    @Test
    fun `filesystem static handlers execute body cache and conditional response branches`(): Unit = kotlinx.coroutines.runBlocking {
        val dir = createTempDirWithFile("asset.txt", "static body")
        val router = Router()
        router.staticFiles("/assets", dir, cacheControl = "no-cache")
        val resolved = assertNotNull(router.resolve(HttpMethod.Head, "/assets/asset.txt"))

        val ordinary = executeStatic(resolved)
        try {
            val response = ordinary.readOutbound<DefaultHttpResponse>()
            assertEquals(HttpResponseStatus.OK, response.status())
            assertEquals("no-cache", response.headers().get(HttpHeaders.CacheControl))
            val etag = assertNotNull(response.headers().get(HttpHeaders.ETag))
            var body = ""
            while (true) {
                val message = ordinary.readOutbound<Any>() ?: break
                if (message is HttpContent) body += message.content().toString(Charsets.UTF_8)
                ReferenceCountUtil.release(message)
            }
            assertEquals("static body", body)

            val conditional = executeStatic(resolved, "other, $etag")
            val notModified = conditional.readOutbound<io.netty.handler.codec.http.FullHttpResponse>()
            assertEquals(HttpResponseStatus.NOT_MODIFIED, notModified.status())
            ReferenceCountUtil.release(notModified)
            conditional.close()

            val wildcard = executeStatic(resolved, "*")
            val wildcardResponse = wildcard.readOutbound<io.netty.handler.codec.http.FullHttpResponse>()
            assertEquals(HttpResponseStatus.NOT_MODIFIED, wildcardResponse.status())
            ReferenceCountUtil.release(wildcardResponse)
            wildcard.close()
        } finally {
            ordinary.close()
        }
    }

    @Test
    fun `classpath static handler executes with fallback classloader and cache header`(): Unit = kotlinx.coroutines.runBlocking {
        val router = Router()
        router.staticResources("/assets", "test-static", cacheControl = "public, max-age=60")
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader
        try {
            thread.contextClassLoader = null
            val resolved = assertNotNull(router.resolve(HttpMethod.Get, "/assets/test.txt"))
            val channel = executeStatic(resolved)
            try {
                val response = channel.readOutbound<DefaultHttpResponse>()
                assertEquals("public, max-age=60", response.headers().get(HttpHeaders.CacheControl))
                ReferenceCountUtil.release(assertNotNull(channel.readOutbound<HttpContent>()))
            } finally {
                channel.close()
            }
        } finally {
            thread.contextClassLoader = original
        }
    }

    @Test
    fun `static prefix boundaries traversal and HEAD fallbacks are enforced`() = runTest {
        val dir = createTempDirWithFile("asset.txt", "body")
        val router = Router()
        router.staticFiles("/assets", dir)
        router.staticResources("/resources", "test-static")

        assertNull(router.resolve(HttpMethod.Get, "/assets-other/asset.txt"))
        assertNull(router.resolve(HttpMethod.Get, "/resources/../test.txt"))
        assertNotNull(router.resolve(HttpMethod.Head, "/assets/asset.txt"))
    }

    @Test
    fun `exact static prefix serves an index without optional cache headers`() = kotlinx.coroutines.runBlocking {
        val dir = createTempDirWithFile("index.html", "index")
        val fileRouter = Router().apply { staticFiles("/assets", dir) }
        val fileChannel = executeStatic(assertNotNull(fileRouter.resolve(HttpMethod.Get, "/assets")))
        try {
            val response = assertNotNull(fileChannel.readOutbound<DefaultHttpResponse>())
            assertNull(response.headers().get(HttpHeaders.CacheControl))
        } finally {
            fileChannel.finishAndReleaseAll()
        }

        val classpathRouter = Router().apply { staticResources("/assets", "test-static") }
        val classpathChannel = executeStatic(
            assertNotNull(classpathRouter.resolve(HttpMethod.Get, "/assets/test.txt")),
        )
        try {
            val response = assertNotNull(classpathChannel.readOutbound<DefaultHttpResponse>())
            assertNull(response.headers().get(HttpHeaders.CacheControl))
        } finally {
            classpathChannel.finishAndReleaseAll()
        }
    }

    @Test
    fun `explicit route takes priority over static handler`() = runTest {
        val dir = createTempDirWithFile("hello.txt", "Hello World")
        val router = Router()
        var handlerCalled = false
        router.get("/static/hello.txt") { handlerCalled = true }
        router.staticFiles("/static", dir)
        val resolved = router.resolve(HttpMethod.Get, "/static/hello.txt")
        assertNotNull(resolved)
        // The route handler should be resolved, not the static handler
    }

    // --- matchPath tests ---

    @Test
    fun `matchPath matches exact path`() {
        val result = Router.matchPath("/users", "/users")
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matchPath matches root path`() {
        val result = Router.matchPath("/", "/")
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `matchPath extracts parameters`() {
        val result = Router.matchPath("/users/{id}", "/users/123")
        assertNotNull(result)
        assertEquals("123", result["id"])
    }

    @Test
    fun `matchPath returns null for different segment count`() {
        val result = Router.matchPath("/users/{id}", "/users")
        assertNull(result)
    }

    @Test
    fun `matchPath returns null for non-matching literal`() {
        val result = Router.matchPath("/users/active", "/users/inactive")
        assertNull(result)
    }

    @Test
    fun `matchPath root does not match non-root`() {
        assertNull(Router.matchPath("/", "/something"))
    }

    @Test
    fun `matchPath extracts partial parameter with prefix`() {
        val result = Router.matchPath("/@{scope}/{name}", "/@acme/analytics")
        assertNotNull(result)
        assertEquals("acme", result["scope"])
        assertEquals("analytics", result["name"])
    }

    @Test
    fun `matchPath partial parameter with suffix`() {
        val result = Router.matchPath("/v{version}.tar", "/v1.2.tar")
        assertNotNull(result)
        assertEquals("1.2", result["version"])
    }

    @Test
    fun `matchPath partial parameter rejects wrong prefix`() {
        val result = Router.matchPath("/@{scope}/{name}", "/!acme/analytics")
        assertNull(result)
    }

    @Test
    fun `partial parameters reject wrong suffix and overlapping affixes`() {
        assertNull(Router.matchPath("/v{version}.tar", "/v1.zip"))
        assertNull(Router.matchPath("/ab{middle}bc", "/abc"))
        assertEquals(emptyMap(), Router.matchPath(emptyList(), emptyList()))
    }

    @Test
    fun `partial parameter route resolves via router`() = runTest {
        val router = Router()
        router.route("/npm") {
            get("/@{scope}/{name}") {}
        }
        val resolved = router.resolve(HttpMethod.Get, "/npm/@acme/analytics-client-browser")
        assertNotNull(resolved)
        assertEquals("acme", resolved.pathParameters["scope"])
        assertEquals("analytics-client-browser", resolved.pathParameters["name"])
    }

    // --- Tailcard parameter tests ---

    @Test
    fun `matchPath tailcard captures all remaining segments`() {
        val result = Router.matchPath("/maven/{path...}", "/maven/bosca/framework-base-ksp/4.7.6/framework-base-ksp-4.7.6.jar")
        assertNotNull(result)
        assertEquals("bosca/framework-base-ksp/4.7.6/framework-base-ksp-4.7.6.jar", result["path"])
    }

    @Test
    fun `matchPath tailcard captures single remaining segment`() {
        val result = Router.matchPath("/maven/{path...}", "/maven/metadata.xml")
        assertNotNull(result)
        assertEquals("metadata.xml", result["path"])
    }

    @Test
    fun `matchPath tailcard matches with no remaining segments`() {
        val result = Router.matchPath("/maven/{path...}", "/maven")
        assertNotNull(result)
        assertEquals("", result["path"])
    }

    @Test
    fun `matchPath tailcard with fixed segments before it`() {
        val result = Router.matchPath("/api/v1/{rest...}", "/api/v1/users/42/posts")
        assertNotNull(result)
        assertEquals("users/42/posts", result["rest"])
    }

    @Test
    fun `matchPath tailcard rejects when fixed prefix does not match`() {
        val result = Router.matchPath("/maven/{path...}", "/npm/something")
        assertNull(result)
    }

    @Test
    fun `tailcard route resolves via router`() = runTest {
        val router = Router()
        router.put("/maven/{path...}") {}
        val resolved = router.resolve(HttpMethod.Put, "/maven/bosca/framework-base-ksp/4.7.6/framework-base-ksp-4.7.6.jar")
        assertNotNull(resolved)
        assertEquals("bosca/framework-base-ksp/4.7.6/framework-base-ksp-4.7.6.jar", resolved.pathParameters["path"])
    }

    @Test
    fun `tailcard route does not shadow non-tailcard routes`() = runTest {
        val router = Router()
        router.get("/maven/status") {}
        router.get("/maven/{path...}") {}
        val resolved = router.resolve(HttpMethod.Get, "/maven/status")
        assertNotNull(resolved)
        assertTrue(resolved.pathParameters.isEmpty())
    }

    // --- matchPathPrefix tests ---

    @Test
    fun `matchPathPrefix matches prefix and returns remainder`() {
        val result = Router.matchPathPrefix("/api", "/api/users")
        assertNotNull(result)
        assertEquals("/users", result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun `matchPathPrefix extracts parameters from prefix`() {
        val result = Router.matchPathPrefix("/users/{id}", "/users/42/posts")
        assertNotNull(result)
        assertEquals("/posts", result.first)
        assertEquals("42", result.second["id"])
    }

    @Test
    fun `matchPathPrefix returns null when path is shorter than prefix`() {
        val result = Router.matchPathPrefix("/api/v1", "/api")
        assertNull(result)
    }

    @Test
    fun `matchPathPrefix root prefix matches everything`() {
        val result = Router.matchPathPrefix("/", "/anything/here")
        assertNotNull(result)
        assertEquals("/anything/here", result.first)
    }

    @Test
    fun `matchPathPrefix exact match returns root remainder`() {
        val result = Router.matchPathPrefix("/api", "/api")
        assertNotNull(result)
        assertEquals("/", result.first)
    }

    // --- Path normalization ---

    @Test
    fun `multiple routes same path different methods`() = runTest {
        val router = Router()
        router.get("/resource") {}
        router.post("/resource") {}

        assertNotNull(router.resolve(HttpMethod.Get, "/resource"))
        assertNotNull(router.resolve(HttpMethod.Post, "/resource"))
    }

    @Test
    fun `trailing slash normalized`() = runTest {
        val router = Router()
        router.get("/hello/") {}
        assertNotNull(router.resolve(HttpMethod.Get, "/hello"))
        assertNotNull(router.resolve(HttpMethod.Get, "/hello/"))
        assertNotNull(router.resolve(HttpMethod.Head, "/hello/"))
    }

    @Test
    fun `double slashes in path normalized`() = runTest {
        val router = Router()
        router.get("//hello//world//") {}
        assertNotNull(router.resolve(HttpMethod.Get, "/hello/world"))
        assertNotNull(router.resolve(HttpMethod.Get, "//hello//world//"))
        assertNotNull(router.resolve(HttpMethod.Head, "//hello//world//"))
    }

    // --- Content type detection ---

    @Test
    fun `contentTypeForPath detects html`() {
        assertEquals(ContentType.Text.Html, contentTypeForPath("page.html"))
    }

    @Test
    fun `contentTypeForPath detects css`() {
        assertEquals(ContentType.Text.Css, contentTypeForPath("style.css"))
    }

    @Test
    fun `contentTypeForPath detects javascript`() {
        assertEquals(ContentType.Text.JavaScript, contentTypeForPath("app.js"))
    }

    @Test
    fun `contentTypeForPath detects json`() {
        assertEquals(ContentType.Application.Json, contentTypeForPath("data.json"))
    }

    @Test
    fun `contentTypeForPath falls back to octet-stream`() {
        assertEquals(ContentType.Application.OctetStream, contentTypeForPath("file.xyz"))
    }

    @Test
    fun `contentTypeForPath handles nested paths`() {
        assertEquals(ContentType.Image.Png, contentTypeForPath("assets/images/logo.png"))
    }

    // --- SSE authentication ---

    @Test
    fun `SSE route within authenticate block inherits auth config`() {
        val router = Router()
        router.authenticate("jwt") {
            sse("/events") {}
        }
        val result = router.resolveSSE("/events")
        assertNotNull(result)
        assertNotNull(result.authConfig)
        assertTrue(result.authConfig!!.providers.contains("jwt"))
    }

    @Test
    fun `prefix SSE overload and parameterized websocket merge route parameters`() {
        val router = Router()
        router.route("/events/{tenant}") {
            sse {}
        }
        router.route("/socket/{tenant}") {
            webSocket("/{room}") {}
        }

        val sse = assertNotNull(router.resolveSSE("/events/acme"))
        assertEquals("acme", sse.pathParameters?.get("tenant"))
        val socket = assertNotNull(router.resolveWebSocket("/socket/acme/general"))
        assertEquals(mapOf("tenant" to "acme", "room" to "general"), socket.pathParameters)
    }

    @Test
    fun `SSE route without authenticate block has null auth config`() {
        val router = Router()
        router.sse("/events") {}
        val result = router.resolveSSE("/events")
        assertNotNull(result)
        assertNull(result.authConfig)
    }

    @Test
    fun `SSE route in nested route within authenticate block inherits auth config`() {
        val router = Router()
        router.route("/api") {
            authenticate("jwt", optional = true) {
                sse("/events") {}
            }
        }
        val result = router.resolveSSE("/api/events")
        assertNotNull(result)
        assertNotNull(result.authConfig)
        assertTrue(result.authConfig!!.providers.contains("jwt"))
        assertTrue(result.authConfig!!.optional)
    }

    // --- WebSocket authentication ---

    @Test
    fun `WebSocket route within authenticate block inherits auth config`() {
        val router = Router()
        router.authenticate("jwt") {
            webSocket("/ws") {}
        }
        val result = router.resolveWebSocket("/ws")
        assertNotNull(result)
        assertNotNull(result.authConfig)
        assertTrue(result.authConfig!!.providers.contains("jwt"))
    }

    @Test
    fun `WebSocket route without authenticate block has null auth config`() {
        val router = Router()
        router.webSocket("/ws") {}
        val result = router.resolveWebSocket("/ws")
        assertNotNull(result)
        assertNull(result.authConfig)
    }

    @Test
    fun `WebSocket route in nested route within authenticate block inherits auth config`() {
        val router = Router()
        router.route("/api") {
            authenticate("jwt", "basic") {
                webSocket("/ws") {}
            }
        }
        val result = router.resolveWebSocket("/api/ws")
        assertNotNull(result)
        assertNotNull(result.authConfig)
        assertEquals(listOf("jwt", "basic"), result.authConfig!!.providers)
    }

    @Test
    fun `pathless child routers resolve HTTP WebSocket and SSE entries`() = runTest {
        val router = Router()
        router.route("") {
            get("/http") {}
            webSocket("/ws") {}
            sse {}
        }

        assertNotNull(router.resolve(HttpMethod.Get, "/http"))
        assertNotNull(router.resolveWebSocket("/ws"))
        assertNotNull(router.resolveSSE("/"))
    }

    @Test
    fun `matching child prefixes can still reject unmatched WebSocket and SSE paths`() {
        val router = Router()
        router.route("/api") {
            webSocket("/socket") {}
            sse("/events") {}
        }

        assertNull(router.resolveWebSocket("/api/missing"))
        assertNull(router.resolveSSE("/api/missing"))
    }

    @Test
    fun `route resolution reuses split path parts across failed parameter candidates`() = runTest {
        val router = Router()
        router.get("/items/{id}/details") {}
        router.get("/items/{id}") {}
        router.head("/items/{id}/head") {}
        router.route("/nested") { get("/{id}") {} }

        assertEquals("42", router.resolve(HttpMethod.Get, "/items/42")?.pathParameters?.get("id"))
        assertEquals("42", router.resolve(HttpMethod.Head, "/items/42")?.pathParameters?.get("id"))
        assertEquals("42", router.resolve(HttpMethod.Get, "/nested/42")?.pathParameters?.get("id"))
        assertNull(router.resolve(HttpMethod.Get, "/other/42"))
    }

    @Test
    fun `path matching covers malformed affixes and every tailcard fixed-prefix shape`() {
        assertNull(Router.matchPath(listOf("{unfinished"), listOf("value")))
        assertNull(Router.matchPath(listOf("unfinished}"), listOf("value")))
        assertNull(Router.matchPath(listOf("literal", "{rest...}"), emptyList()))
        assertEquals(
            mapOf("tenant" to "alpha", "rest" to "one/two"),
            Router.matchPath(
                listOf("{tenant}", "{rest...}"),
                listOf("alpha", "one", "two"),
            ),
        )
        assertEquals(
            mapOf("rest" to "one"),
            Router.matchPath(listOf("literal", "{rest...}"), listOf("literal", "one")),
        )
    }

    // --- Helpers ---

    private fun createTempDirWithFile(filename: String, content: String): File {
        val dir = File.createTempFile("router-test-", "").also {
            it.delete()
            it.mkdirs()
        }
        dir.deleteOnExit()
        val file = File(dir, filename)
        file.writeText(content)
        file.deleteOnExit()
        return dir
    }

    private suspend fun executeStatic(resolved: ResolvedRoute, ifNoneMatch: String? = null): EmbeddedChannel {
        val application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        val channel = EmbeddedChannel(object : ChannelInboundHandlerAdapter() {})
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.header(HttpHeaders.IfNoneMatch) } returns ifNoneMatch
        val response = ServerResponse(channel.pipeline().firstContext())
        val call = ServerCall(request, response, application = application)
        resolved.handler(RoutingContext(call, application))
        channel.runPendingTasks()
        return channel
    }
}
