package bosca.bml.server

import bosca.bml.project.CompiledProject
import bosca.bml.project.ContentDigest
import bosca.bml.render.BML_ANALYTICS_SESSION_COOKIE
import bosca.bml.render.BML_INSTALLATION_COOKIE
import bosca.bml.render.BML_LOCALE_COOKIE
import bosca.bml.render.BmlComponentInfo
import bosca.bml.render.BmlDeferredRenderer
import bosca.bml.render.BmlIslandActionDispatcher
import bosca.bml.render.IslandActionResult
import bosca.bml.render.BmlLocales
import bosca.bml.render.BmlLocalePolicy
import bosca.bml.render.BmlPageRenderer
import bosca.bml.render.BmlSharedCacheRevision
import bosca.bml.render.RenderContext
import bosca.bml.render.bmlPageSessionStateKey
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.server.netty.NettyServerEngine
import com.sun.net.httpserver.HttpServer
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real HTTP coverage for the public-shell/private-fragment cache boundary. */
class BmlDeferredRoutesTest {

    @Test
    fun `a real caching proxy reuses the public shell but never the identity-bound fragment`() {
        proxyCache.clear()
        sharedRenders.set(0)
        val aliceShell = proxyGet(
            "/shared/42?view=grid&lang=fr",
            "Authorization" to "Bearer alice",
            "Accept-Language" to "fr",
        )
        val bobShell = proxyGet(
            "/shared/42?view=grid&lang=fr",
            "Authorization" to "Bearer bob",
            "Accept-Language" to "en",
        )

        assertEquals(200, aliceShell.statusCode())
        assertEquals(aliceShell.body(), bobShell.body())
        assertEquals(1, sharedRenders.get(), "the second shell request must be served by the proxy cache")

        val body =
            """{"props":{"accountId":"acct-7"},"page":"/shared/{id}","path":"/shared/42","query":{}}"""
        val aliceFragment = proxyPost(
            "/_bml/deferred/SharedPage%3Aaccount",
            body,
            "Authorization" to "Bearer alice",
        )
        val bobFragment = proxyPost(
            "/_bml/deferred/SharedPage%3Aaccount",
            body,
            "Authorization" to "Bearer bob",
        )

        assertTrue("token=Bearer alice" in aliceFragment.body(), aliceFragment.body())
        assertTrue("token=Bearer bob" in bobFragment.body(), bobFragment.body())
        assertEquals("private, no-store", header(aliceFragment, "Cache-Control"))
        assertEquals("private, no-store", header(bobFragment, "Cache-Control"))
        assertEquals(null, header(aliceFragment, "Cloudflare-CDN-Cache-Control"))
        assertEquals(null, header(bobFragment, "Cloudflare-CDN-Cache-Control"))
    }

    @Test
    fun `shared shell is independent of caller identity and carries an edge cache policy`() {
        val first = get(
            "/shared/42?view=grid&lang=fr",
            "Authorization" to "Bearer alice",
            "Cookie" to "_bat=alice;$BML_INSTALLATION_COOKIE=install-a;$BML_LOCALE_COOKIE=en",
            "Accept-Language" to "fr",
        )
        val second = get(
            "/shared/42?view=grid&lang=fr",
            "Authorization" to "Bearer bob",
            "Cookie" to "_bat=bob;$BML_INSTALLATION_COOKIE=install-b;$BML_LOCALE_COOKIE=en",
            "Accept-Language" to "en",
        )

        assertEquals(200, first.statusCode())
        assertEquals(first.body(), second.body())
        assertTrue("token=null" in first.body(), first.body())
        assertTrue("cookies=0" in first.body(), first.body())
        assertTrue("lang=fr" in first.body(), first.body())
        assertTrue("data-bml-page-locale=\"fr\"" in first.body(), first.body())
        assertTrue("id=42" in first.body(), first.body())
        assertTrue("view=grid" in first.body(), first.body())
        assertEquals("public, max-age=0, must-revalidate", header(first, "Cache-Control"))
        assertEquals(
            "public, max-age=600, stale-while-revalidate=600",
            header(first, "Cloudflare-CDN-Cache-Control"),
        )
        assertTrue(header(first, "ETag")?.startsWith("\"") == true)
        // The URL alone selects the shell, so an edge needs no Vary support to keep variants apart.
        assertTrue(first.headers().allValues("Vary").none { it.contains("Accept-Language", ignoreCase = true) })
        assertTrue(first.headers().allValues("Set-Cookie").isEmpty(), first.headers().map().toString())
    }

    @Test
    fun `a shared page whose locale would be negotiated renders privately and is never edge-cached`() {
        proxyCache.clear()
        val french = proxyGet("/shared/42?view=grid", "Authorization" to "Bearer alice", "Accept-Language" to "fr")
        val english = proxyGet("/shared/42?view=grid", "Authorization" to "Bearer bob", "Accept-Language" to "en")

        assertEquals(200, french.statusCode())
        assertTrue("lang=fr" in french.body(), french.body())
        assertTrue("lang=en" in english.body(), english.body())
        // Private, identity-bearing renders: never stored by an edge that ignores Vary.
        assertTrue("token=Bearer alice" in french.body(), french.body())
        assertEquals("private, no-store", header(french, "Cache-Control"))
        assertEquals(null, header(french, "Cloudflare-CDN-Cache-Control"))
        assertTrue(proxyCache.keys.none { it.startsWith("/shared/42?view=grid|") }, proxyCache.keys.toString())
    }

    @Test
    fun `shared shell revision answers conditional requests before rendering and changes after an event`() {
        val initialModified = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        revalidatedEtag.set("metadata-etag-1")
        revalidatedModified.set(initialModified)
        revalidatedRenders.set(0)

        val first = get("/revalidated?lang=en")
        val firstEtag = header(first, "ETag") ?: error("missing ETag")
        assertEquals(200, first.statusCode())
        assertTrue("metadata-etag-1" in first.body(), first.body())
        assertTrue("data-bml-shared-identity" in first.body(), first.body())
        assertEquals(null, header(first, "Last-Modified"))
        assertEquals(1, revalidatedRenders.get())

        val unchanged = get("/revalidated?lang=en", "If-None-Match" to "W/$firstEtag")
        assertEquals(304, unchanged.statusCode())
        assertEquals("", unchanged.body())
        assertEquals(1, revalidatedRenders.get(), "a matching revision must bypass page rendering")
        assertEquals(firstEtag, header(unchanged, "ETag"))

        val dateOnly = get("/revalidated?lang=en", "If-Modified-Since" to "Wed, 16 Sep 2099 20:00:00 GMT")
        assertEquals(200, dateOnly.statusCode())
        assertEquals(2, revalidatedRenders.get(), "a date alone cannot validate the compiled representation")

        revalidatedModified.set(initialModified.plusSeconds(1))
        val modifiedOnly = get("/revalidated?lang=en", "If-None-Match" to firstEtag)
        val modifiedOnlyEtag = header(modifiedOnly, "ETag") ?: error("missing changed ETag")
        assertEquals(200, modifiedOnly.statusCode())
        assertTrue(firstEtag != modifiedOnlyEtag, "the metadata modified time participates in the shell ETag")
        assertEquals(3, revalidatedRenders.get())

        revalidatedEtag.set("metadata-etag-2")
        revalidatedModified.set(initialModified.plusSeconds(2))
        val changed = get("/revalidated?lang=en", "If-None-Match" to modifiedOnlyEtag)
        assertEquals(200, changed.statusCode())
        assertTrue("metadata-etag-2" in changed.body(), changed.body())
        assertTrue(modifiedOnlyEtag != header(changed, "ETag"))
        assertEquals(4, revalidatedRenders.get())
    }

    @Test
    fun `a wildcard If-None-Match is answered only after the page has rendered`() {
        revalidatedEtag.set("metadata-etag-wildcard")
        revalidatedModified.set(Instant.parse("2026-09-16T20:00:00Z"))
        revalidatedRenders.set(0)

        val response = get("/revalidated?lang=en", "If-None-Match" to "*")

        // The page could still redirect or report not-found, so the wildcard never skips the render.
        assertEquals(304, response.statusCode())
        assertEquals(1, revalidatedRenders.get())
    }

    @Test
    fun `revision drift during rendering falls back to the completed body validator`() {
        revalidatedEtag.set("metadata-etag-before-render")
        revalidatedModified.set(Instant.parse("2026-09-16T20:00:00Z"))
        revisionDriftsDuringRender.set(true)

        val response = get("/revalidated?lang=en")

        assertEquals(200, response.statusCode())
        assertTrue("metadata-etag-before-render" in response.body(), response.body())
        assertEquals(
            "\"${ContentDigest.sha256(response.body().toByteArray()).removePrefix("sha256:")}\"",
            header(response, "ETag"),
        )
        assertEquals(null, header(response, "Last-Modified"))
    }

    @Test
    fun `caching proxy serves stale shell while a Bosca revision revalidates in the background`() {
        proxyCache.clear()
        proxyRevalidating.clear()
        revalidatedEtag.set("metadata-etag-before-event")
        revalidatedModified.set(Instant.now())
        revalidatedRenders.set(0)

        val initial = proxyGet("/revalidated?lang=en")
        assertEquals(200, initial.statusCode())
        assertTrue("metadata-etag-before-event" in initial.body(), initial.body())
        expireProxyEntry("/revalidated?lang=en")

        // Simulates the Bosca event handler updating the cached metadata value, ETag, and modified time.
        revalidatedEtag.set("metadata-etag-after-event")
        revalidatedModified.set(Instant.now().plusSeconds(1))

        val stale = proxyGet("/revalidated?lang=en")
        assertTrue("metadata-etag-before-event" in stale.body(), stale.body())
        assertEquals("UPDATING", header(stale, "CF-Cache-Status"))

        var refreshed: HttpResponse<String>? = null
        var attempts = 0
        while (refreshed == null && attempts++ < 100) {
            val candidate = proxyGet("/revalidated?lang=en")
            if ("metadata-etag-after-event" in candidate.body()) {
                refreshed = candidate
            } else {
                Thread.sleep(10)
            }
        }
        val finalResponse = refreshed ?: error("the proxy never installed the background refresh")
        assertEquals("HIT", header(finalResponse, "CF-Cache-Status"))
        assertEquals(2, revalidatedRenders.get(), "one initial render and one changed revalidation")
    }

    @Test
    fun `server state under a deferred boundary makes the shell private and establishes the session`() {
        val response = get("/component-shell")
        assertEquals(200, response.statusCode())
        assertEquals("private, no-store", header(response, "Cache-Control"))
        assertEquals(null, header(response, "Cloudflare-CDN-Cache-Control"))
        assertTrue(
            "data-bml-page=\"/component-shell\" data-bml-page-canonical" in response.body(),
            response.body(),
        )
        assertTrue("data-bml-page-path=\"/component-shell\"" in response.body(), response.body())
        assertTrue(
            response.headers().allValues("Set-Cookie").any { it.startsWith("bml_session=") },
            response.headers().map().toString(),
        )
    }

    @Test
    fun `runtime refuses shared caching when an eager component evaluates identity feature flags`() {
        val response = get("/unsafe", "Authorization" to "Bearer current-token")
        assertEquals(200, response.statusCode())
        assertEquals("private, no-store", header(response, "Cache-Control"))
        assertEquals(null, header(response, "Cloudflare-CDN-Cache-Control"))
        assertTrue("Bearer current-token" in response.body(), response.body())
    }

    @Test
    fun `runtime refuses direct feature flag evaluation in a shared shell`() {
        val response = get("/unsafe-direct?lang=en")

        assertEquals(500, response.statusCode())
        assertEquals("private, no-store", header(response, "Cache-Control"))
        assertEquals(null, header(response, "Cloudflare-CDN-Cache-Control"))
    }

    @Test
    fun `deferred render receives current request identity and explicit page context`() {
        val response = post(
            "/_bml/deferred/SharedPage%3Aaccount",
            """{"props":{"accountId":"acct-7"},"page":"/shared/{id}","path":"/shared/42","query":{"view":"activity"},"locale":"fr"}""",
            "Authorization" to "Bearer current-token",
            "X-Installation-ID" to "installation-current",
            "X-BA-Session-ID" to "analytics-current",
            "Cookie" to "_bat=stale-cookie;$BML_LOCALE_COOKIE=en",
        )

        assertEquals(200, response.statusCode())
        assertEquals("private, no-store", header(response, "Cache-Control"))
        assertTrue("token=Bearer current-token" in response.body(), response.body())
        assertTrue("account=acct-7" in response.body(), response.body())
        assertTrue("id=42" in response.body(), response.body())
        assertTrue("view=activity" in response.body(), response.body())
        assertTrue("lang=fr" in response.body(), response.body())
        assertTrue("path=/shared/42" in response.body(), response.body())
        assertTrue("installation=installation-current" in response.body(), response.body())
        val cookies = response.headers().allValues("Set-Cookie").joinToString(";")
        assertTrue(cookies.isEmpty(), cookies)
    }

    @Test
    fun `identity bootstrap is private and deferred renders do not mint analytics sessions`() {
        val bootstrap = post("/_bml/identity", "{}")
        assertEquals(204, bootstrap.statusCode())
        assertEquals("private, no-store", header(bootstrap, "Cache-Control"))
        assertTrue(
            bootstrap.headers().allValues("Set-Cookie").any { BML_ANALYTICS_SESSION_COOKIE in it },
            bootstrap.headers().map().toString(),
        )

        val deferred = post(
            "/_bml/deferred/SharedPage%3Aaccount",
            """{"props":{"accountId":"acct-7"},"page":"/shared/{id}","path":"/shared/42","query":{}}""",
        )
        assertEquals(200, deferred.statusCode())
        assertTrue(deferred.headers().allValues("Set-Cookie").isEmpty(), deferred.headers().map().toString())
    }

    @Test
    fun `public immutable assets never carry browser identity cookies`() {
        val response = get("/_bml/app.js")

        assertEquals(200, response.statusCode())
        assertEquals("public, max-age=31536000, immutable", header(response, "Cache-Control"))
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty(), response.headers().map().toString())
    }

    @Test
    fun `component deferred renderer is available only through pages that own the component`() {
        val accepted = post(
            "/_bml/deferred/component%3Aaccount-shell%3Aprivate",
            """{"props":{},"page":"/component-shell","path":"/component-shell","query":{}}""",
        )
        assertEquals(200, accepted.statusCode())
        assertTrue("private component" in accepted.body(), accepted.body())

        val rejected = post(
            "/_bml/deferred/component%3Aaccount-shell%3Aprivate",
            """{"props":{},"page":"/shared/{id}","path":"/shared/42","query":{}}""",
        )
        assertEquals(422, rejected.statusCode())
    }

    @Test
    fun `configured not found page uses its render path for deferred requests`() {
        val response = get(
            "/missing",
            "Authorization" to "Bearer fallback-user",
            "X-Installation-ID" to "fallback-installation",
        )
        assertEquals(404, response.statusCode())
        assertEquals("private, no-store", header(response, "Cache-Control"))
        assertEquals(null, header(response, "Cloudflare-CDN-Cache-Control"))
        assertTrue("token=Bearer fallback-user" in response.body(), response.body())
        assertTrue("installation=fallback-installation" in response.body(), response.body())
        assertTrue("data-bml-page=\"/not-found\"" in response.body(), response.body())
        assertTrue("data-bml-page-path=\"/not-found\"" in response.body(), response.body())
        val responseCookies = response.headers().allValues("Set-Cookie")
        assertTrue(responseCookies.any { it.startsWith("$BML_INSTALLATION_COOKIE=fallback-installation") })
        assertTrue(responseCookies.any { it.startsWith("$BML_ANALYTICS_SESSION_COOKIE=") })
        val sessionCookie = responseCookies
            .first { it.startsWith("bml_session=") }
            .substringBefore(';')

        val deferred = post(
            "/_bml/deferred/NotFoundPage%3Aprivate",
            """{"props":{},"page":"/not-found","path":"/not-found","query":{}}""",
            "Cookie" to sessionCookie,
        )
        assertEquals(200, deferred.statusCode())
        assertTrue("not found private" in deferred.body(), deferred.body())
    }

    @Test
    fun `configured error page restores identity after a shared render fails`() {
        val response = get(
            "/shared-error",
            "Authorization" to "Bearer fallback-user",
            "X-Installation-ID" to "fallback-installation",
        )

        assertEquals(500, response.statusCode())
        assertEquals("private, no-store", header(response, "Cache-Control"))
        assertEquals(null, header(response, "Cloudflare-CDN-Cache-Control"))
        assertTrue("token=Bearer fallback-user" in response.body(), response.body())
        assertTrue("installation=fallback-installation" in response.body(), response.body())
        val responseCookies = response.headers().allValues("Set-Cookie")
        assertTrue(responseCookies.any { it.startsWith("$BML_INSTALLATION_COOKIE=fallback-installation") })
        assertTrue(responseCookies.any { it.startsWith("$BML_ANALYTICS_SESSION_COOKIE=") })
    }

    @Test
    fun `deferred endpoint rejects unknown malformed mismatched and unauthenticated requests privately`() {
        val unknown = post(
            "/_bml/deferred/missing",
            """{"props":{},"page":"/shared/{id}","path":"/shared/42","query":{}}""",
        )
        assertEquals(404, unknown.statusCode())
        assertEquals("private, no-store", header(unknown, "Cache-Control"))

        val malformed = post("/_bml/deferred/SharedPage%3Aaccount", """{"path":"/shared/42"}""")
        assertEquals(422, malformed.statusCode())
        assertEquals("private, no-store", header(malformed, "Cache-Control"))

        val mismatch = post(
            "/_bml/deferred/SharedPage%3Aaccount",
            """{"props":{},"page":"/shared/{id}","path":"/other","query":{}}""",
        )
        assertEquals(422, mismatch.statusCode())
        assertEquals("private, no-store", header(mismatch, "Cache-Control"))

        val protected = post(
            "/_bml/deferred/MembersPage%3Aaccount",
            """{"props":{},"page":"/members/{id}","path":"/members/42","query":{}}""",
        )
        assertEquals(401, protected.statusCode())
        assertEquals("private, no-store", header(protected, "Cache-Control"))
    }

    @Test
    fun `stateful deferred render renews the page session used by its action`() {
        val shell = get("/stateful")
        val sessionCookie = shell.headers().allValues("Set-Cookie")
            .first { it.startsWith("bml_session=") }
            .substringBefore(';')
        val rendered = post(
            "/_bml/deferred/StatefulPage%3Acounter",
            """{"props":{},"page":"/stateful","path":"/stateful","query":{}}""",
            "Cookie" to sessionCookie,
        )
        assertEquals(200, rendered.statusCode())
        assertTrue(
            rendered.headers().allValues("Set-Cookie").any {
                it.startsWith("$sessionCookie;") && "Max-Age=" in it
            },
            rendered.headers().map().toString(),
        )

        val action = post(
            "/_bml/action",
            """{"page":"/stateful","path":"/stateful","stateKey":"counter","method":"increment","state":"","args":[],"renderView":true}""",
            "Cookie" to sessionCookie,
        )

        assertEquals(200, action.statusCode())
        assertTrue("Count: 1" in action.body(), action.body())
    }

    @Test
    fun `stateful deferred render never creates a missing session`() {
        val response = post(
            "/_bml/deferred/StatefulPage%3Acounter",
            """{"props":{},"page":"/stateful","path":"/stateful","query":{}}""",
        )

        assertEquals(410, response.statusCode())
        assertTrue(
            response.headers().allValues("Set-Cookie").none { it.startsWith("bml_session=") },
            response.headers().map().toString(),
        )
    }

    @Test
    fun `deferred redirects and failures do not replace the page session`() {
        val shell = get("/stateful")
        val sessionCookie = shell.headers().allValues("Set-Cookie")
            .first { it.startsWith("bml_session=") }
            .substringBefore(';')
        val redirect = post(
            "/_bml/deferred/StatefulPage%3Acounter",
            """{"props":{"redirect":true},"page":"/stateful","path":"/stateful","query":{}}""",
            "Cookie" to sessionCookie,
        )
        val response = post(
            "/_bml/deferred/StatefulPage%3Acounter",
            """{"props":{"fail":true},"page":"/stateful","path":"/stateful","query":{}}""",
            "Cookie" to sessionCookie,
        )

        assertEquals(409, redirect.statusCode())
        assertEquals("/after-deferred", header(redirect, "X-BML-Redirect"))
        assertEquals(500, response.statusCode())
        assertTrue(
            (redirect.headers().allValues("Set-Cookie") + response.headers().allValues("Set-Cookie"))
                .none { it.startsWith("bml_session=") },
            response.headers().map().toString(),
        )
    }

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun proxyGet(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$proxyPort$path")).GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(path: String, body: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun proxyPost(path: String, body: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$proxyPort$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun header(response: HttpResponse<*>, name: String): String? =
        response.headers().firstValue(name).orElse(null)

    companion object {
        private data class CachedResponse(
            val status: Int,
            val headers: Map<String, List<String>>,
            val body: ByteArray,
            val cachedAtMillis: Long,
        )

        private var port = 0
        private var proxyPort = 0
        private lateinit var engine: NettyServerEngine
        private lateinit var proxy: HttpServer
        private val client = HttpClient.newHttpClient()
        private val proxyCache = ConcurrentHashMap<String, CachedResponse>()
        private val proxyRevalidating = ConcurrentHashMap.newKeySet<String>()
        // Behaves like an edge that ignores Vary for HTML (Cloudflare's default): the cache key is the URL
        // alone, so correctness cannot depend on Accept-Language variation.
        private val proxyVaryHeaders = emptySet<String>()
        private val sharedRenders = AtomicInteger()
        private val revalidatedRenders = AtomicInteger()
        private val revalidatedEtag = AtomicReference("metadata-etag-1")
        private val revalidatedModified = AtomicReference(Instant.parse("2026-09-16T20:00:00Z"))
        private val revisionDriftsDuringRender = AtomicBoolean()

        @JvmStatic
        @BeforeClass
        fun boot() {
            port = ServerSocket(0).use { it.localPort }
            val sharedDeferred = object : BmlDeferredRenderer {
                override val id = "SharedPage:account"
                override val pageRoute = "/shared/{id}"
                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                    ctx.writer.markup(
                        "<section>token=${ctx.token};account=${props["accountId"]};id=${ctx.params["id"]};" +
                            "view=${ctx.query["view"]};lang=${ctx.lang};path=${ctx.requestPath};" +
                            "installation=${ctx.cookies[BML_INSTALLATION_COOKIE]}</section>",
                    )
                }
            }
            val protectedDeferred = object : BmlDeferredRenderer {
                override val id = "MembersPage:account"
                override val pageRoute = "/members/{id}"
                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                    ctx.writer.markup("<section>members</section>")
                }
            }
            val counterDispatcher = object : BmlIslandActionDispatcher {
                override val stateKey = "counter"
                override val serverScoped = true
                override suspend fun dispatch(
                    ctx: RenderContext,
                    method: String,
                    state: String,
                    args: JsonArray,
                    instanceKey: String,
                ): IslandActionResult {
                    check(method == "increment")
                    val next = (ctx.session?.get(instanceKey)?.toIntOrNull() ?: 0) + 1
                    ctx.session?.put(instanceKey, next.toString())
                    return IslandActionResult(null, "<span>Count: $next</span>")
                }
            }
            val statefulDeferred = object : BmlDeferredRenderer {
                override val id = "StatefulPage:counter"
                override val pageRoute = "/stateful"
                override val hasServerState = true
                override val islandActionDispatchers = listOf(counterDispatcher)
                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                    ctx.session?.putIfAbsent(bmlPageSessionStateKey(ctx.requestPath, "counter"), "0")
                    if (props["redirect"] == true) ctx.redirectTo = "/after-deferred"
                    if (props["fail"] == true) error("deferred render failed after initializing state")
                    ctx.writer.markup("<span>Count: 0</span>")
                }
            }
            val componentDeferred = object : BmlDeferredRenderer {
                override val id = "component:account-shell:private"
                override val ownerComponentTag = "account-shell"
                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                    ctx.writer.markup("<span>private component</span>")
                }
            }
            val notFoundDeferred = object : BmlDeferredRenderer {
                override val id = "NotFoundPage:private"
                override val pageRoute = "/not-found"
                override val hasServerState = true
                override suspend fun render(ctx: RenderContext, props: Map<String, Any?>) {
                    ctx.writer.markup("<span>not found private</span>")
                }
            }
            val sharedPage = object : BmlPageRenderer {
                override val route = "/shared/{id}"
                override val sharedCacheMaxAgeSeconds = 600L
                override val deferredRenderers = listOf(sharedDeferred)
                override suspend fun render(ctx: RenderContext) {
                    sharedRenders.incrementAndGet()
                    ctx.writer.markup(
                        "<html lang=\"${ctx.lang}\"><body>token=${ctx.token};cookies=${ctx.cookies.size};" +
                            "id=${ctx.params["id"]};view=${ctx.query["view"]};lang=${ctx.lang}</body></html>",
                    )
                }
            }
            val revalidatedPage = object : BmlPageRenderer {
                override val route = "/revalidated"
                override val sharedCacheMaxAgeSeconds = 30L
                override val sharedCacheStaleWhileRevalidateSeconds = 300L
                override suspend fun render(ctx: RenderContext) {
                    revalidatedRenders.incrementAndGet()
                    val renderedEtag = revalidatedEtag.get()
                    if (revisionDriftsDuringRender.compareAndSet(true, false)) {
                        revalidatedEtag.set("metadata-etag-after-render-start")
                        revalidatedModified.set(revalidatedModified.get().plusSeconds(1))
                    }
                    ctx.writer.markup(
                        "<html><body>etag=$renderedEtag</body></html>",
                    )
                }
            }
            val membersPage = object : BmlPageRenderer {
                override val route = "/members/{id}"
                override val requiresAuth = true
                override val deferredRenderers = listOf(protectedDeferred)
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>members</body></html>")
                }
            }
            val statefulPage = object : BmlPageRenderer {
                override val route = "/stateful"
                override val deferredRenderers = listOf(statefulDeferred)
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>stateful shell</body></html>")
                }
            }
            val componentShell = object : BmlPageRenderer {
                override val route = "/component-shell"
                override val sharedCacheMaxAgeSeconds = 600L
                override val componentTags = listOf("account-shell")
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body><code>data-bml-page=example</code>component shell</body></html>")
                }
            }
            val missingPage = object : BmlPageRenderer {
                override val route = "/missing"
                override val sharedCacheMaxAgeSeconds = 600L
                override suspend fun render(ctx: RenderContext) {
                    ctx.notFound = true
                }
            }
            val notFoundPage = object : BmlPageRenderer {
                override val route = "/not-found"
                override val deferredRenderers = listOf(notFoundDeferred)
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup(
                        "<html><body>token=${ctx.token};installation=${ctx.cookies[BML_INSTALLATION_COOKIE]};" +
                            "<div data-bml-deferred=\"NotFoundPage:private\"></div>" +
                            "<script type=\"application/json\" data-bml-page=\"/not-found\" " +
                            "data-bml-page-canonical></script></body></html>",
                    )
                }
            }
            val sharedErrorPage = object : BmlPageRenderer {
                override val route = "/shared-error"
                override val sharedCacheMaxAgeSeconds = 600L
                override suspend fun render(ctx: RenderContext) {
                    error("shared render failed")
                }
            }
            val errorPage = object : BmlPageRenderer {
                override val route = "/error"
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup(
                        "<html><body>token=${ctx.token};installation=${ctx.cookies[BML_INSTALLATION_COOKIE]}</body></html>",
                    )
                }
            }
            val unsafe = object : BmlPageRenderer {
                override val route = "/unsafe"
                override val sharedCacheMaxAgeSeconds = 600L
                override val componentTags = listOf("offer")
                override suspend fun render(ctx: RenderContext) {
                    ctx.writer.markup("<html><body>${ctx.token}</body></html>")
                }
            }
            val unsafeDirect = object : BmlPageRenderer {
                override val route = "/unsafe-direct"
                override val sharedCacheMaxAgeSeconds = 600L
                override suspend fun render(ctx: RenderContext) {
                    ctx.featureFlags.enabled("offer")
                    ctx.writer.markup("<html><body>unsafe</body></html>")
                }
            }
            val server = BmlServer(
                project = CompiledProject(name = "deferred-test", version = "1"),
                pages = listOf(
                    sharedPage,
                    revalidatedPage,
                    membersPage,
                    statefulPage,
                    componentShell,
                    missingPage,
                    notFoundPage,
                    sharedErrorPage,
                    errorPage,
                    unsafe,
                    unsafeDirect,
                ),
                port = port,
                dev = false,
                localePolicy = BmlLocalePolicy.static(BmlLocales(listOf("en", "fr"))),
                sharedCacheRevisionProvider = { request ->
                    if (request.pageRoute == "/revalidated") {
                        BmlSharedCacheRevision.fromMetadata(revalidatedEtag.get(), revalidatedModified.get())
                    } else {
                        null
                    }
                },
                globalJs = "console.log('deferred-test')",
                notFoundRoute = "/not-found",
                errorRoute = "/error",
                components = listOf(
                    BmlComponentInfo(
                        tag = "account-shell",
                        scope = null,
                        styles = "",
                        deps = listOf("private-card"),
                        eagerDeps = emptyList(),
                        deferredRenderers = listOf(componentDeferred),
                    ),
                    BmlComponentInfo(
                        tag = "private-card",
                        scope = null,
                        styles = "",
                        deps = emptyList(),
                        hasServerState = true,
                    ),
                    BmlComponentInfo(
                        tag = "offer",
                        scope = null,
                        styles = "",
                        deps = emptyList(),
                        hasEagerFeatureFlags = true,
                    ),
                ),
            )
            val app = BoscaApplication(
                ApplicationConfig.load("bosca:\n  server:\n    port: $port\n    drain-timeout-ms: 0\n    development: false\n".byteInputStream()),
            )
            server.install(app)
            app.freezeMiddleware()
            engine = NettyServerEngine(app, port)
            thread(isDaemon = true, name = "bml-deferred-routes-test-server") { engine.start() }
            repeat(100) {
                try {
                    java.net.Socket("localhost", port).close()
                    startCacheProxy()
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
            if (::proxy.isInitialized) proxy.stop(0)
            if (::engine.isInitialized) engine.stopWithoutHalting()
        }

        private fun startCacheProxy() {
            proxyPort = ServerSocket(0).use { it.localPort }
            proxy = HttpServer.create(InetSocketAddress("localhost", proxyPort), 0)
            proxy.createContext("/") { exchange ->
                val language = if ("accept-language" in proxyVaryHeaders) {
                    exchange.requestHeaders.getFirst("Accept-Language").orEmpty()
                } else {
                    ""
                }
                val cacheKey = "${exchange.requestURI}|$language"
                val method = exchange.requestMethod
                val requestHeaders = listOf("Authorization", "Cookie", "Accept-Language", "Content-Type")
                    .associateWith { exchange.requestHeaders[it].orEmpty() }
                    .filterValues { it.isNotEmpty() }
                val requestBody = if (method == "GET") ByteArray(0) else exchange.requestBody.readAllBytes()
                val cached = proxyCache[cacheKey].takeIf { method == "GET" }
                val now = System.currentTimeMillis()
                val freshSeconds = cached?.let { cacheDirective(it, "max-age") }
                val staleSeconds = cached?.let { cacheDirective(it, "stale-while-revalidate") }
                val ageMillis = cached?.let { now - it.cachedAtMillis }
                val (response, cacheStatus) = when {
                    cached == null -> fetchOrigin(exchange.requestURI, method, requestHeaders, requestBody, null)
                        .also { candidate -> maybeStore(cacheKey, method, candidate) } to "MISS"
                    freshSeconds != null && ageMillis != null && ageMillis <= freshSeconds * 1_000 ->
                        cached to "HIT"
                    freshSeconds != null && staleSeconds != null && ageMillis != null &&
                        ageMillis <= (freshSeconds + staleSeconds) * 1_000 -> {
                        revalidateInBackground(cacheKey, exchange.requestURI, requestHeaders, cached)
                        cached to "UPDATING"
                    }
                    else -> fetchOrigin(exchange.requestURI, method, requestHeaders, requestBody, cached)
                        .also { candidate -> maybeStore(cacheKey, method, candidate) } to "EXPIRED"
                }
                response.headers.forEach { (name, values) ->
                    if (name.lowercase() !in setOf("content-length", "transfer-encoding")) {
                        values.forEach { value -> exchange.responseHeaders.add(name, value) }
                    }
                }
                exchange.responseHeaders.set("CF-Cache-Status", cacheStatus)
                exchange.sendResponseHeaders(response.status, response.body.size.toLong())
                exchange.responseBody.use { it.write(response.body) }
            }
            proxy.start()
        }

        private fun fetchOrigin(
            uri: java.net.URI,
            method: String,
            headers: Map<String, List<String>>,
            body: ByteArray,
            cached: CachedResponse?,
        ): CachedResponse {
            val target = URI.create("http://localhost:$port$uri")
            val request = HttpRequest.newBuilder(target).apply {
                headers.forEach { (name, values) -> values.forEach { value -> header(name, value) } }
                if (cached != null) {
                    cached.header("ETag")?.let { header("If-None-Match", it) }
                }
                method(
                    method,
                    if (method == "GET") HttpRequest.BodyPublishers.noBody()
                    else HttpRequest.BodyPublishers.ofByteArray(body),
                )
            }.build()
            val origin = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val now = System.currentTimeMillis()
            if (origin.statusCode() == 304 && cached != null) {
                return cached.copy(
                    headers = cached.headers + origin.headers().map(),
                    cachedAtMillis = now,
                )
            }
            return CachedResponse(origin.statusCode(), origin.headers().map(), origin.body(), now)
        }

        private fun maybeStore(cacheKey: String, method: String, candidate: CachedResponse) {
            val cacheControl = candidate.header("Cache-Control").orEmpty()
            if (method == "GET" && candidate.status == 200 && cacheControl.contains("public")) {
                proxyCache[cacheKey] = candidate
            }
        }

        private fun revalidateInBackground(
            cacheKey: String,
            uri: java.net.URI,
            headers: Map<String, List<String>>,
            cached: CachedResponse,
        ) {
            if (!proxyRevalidating.add(cacheKey)) return
            thread(isDaemon = true, name = "bml-cache-proxy-revalidate") {
                try {
                    val refreshed = fetchOrigin(uri, "GET", headers, ByteArray(0), cached)
                    maybeStore(cacheKey, "GET", refreshed)
                } finally {
                    proxyRevalidating.remove(cacheKey)
                }
            }
        }

        private fun expireProxyEntry(path: String, language: String = "") {
            val cacheKey = "$path|$language"
            proxyCache.computeIfPresent(cacheKey) { _, cached ->
                val freshSeconds = cacheDirective(cached, "max-age") ?: error("missing max-age")
                cached.copy(cachedAtMillis = System.currentTimeMillis() - ((freshSeconds + 1) * 1_000))
            }
        }

        private fun cacheDirective(response: CachedResponse, name: String): Long? =
            response.header("Cloudflare-CDN-Cache-Control")
                ?.split(',')
                ?.map(String::trim)
                ?.firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
                ?.substringAfter('=', missingDelimiterValue = "")
                ?.toLongOrNull()

        private fun CachedResponse.header(name: String): String? = headers.entries
            .firstOrNull { (headerName) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }
}
