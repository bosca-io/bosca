package bosca.server.middleware

import bosca.server.BoscaApplication
import bosca.server.HttpHeaders
import bosca.server.HttpMethod
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorsMiddlewareTest {

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.isActive } returns true
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } returns future
        return ctx
    }

    private fun createCall(
        method: HttpMethod = HttpMethod.Get,
        headers: Map<String, String> = emptyMap()
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.httpMethod } returns method
        every { request.headers[any()] } returns null
        headers.forEach { (k, v) -> every { request.headers[k] } returns v }

        val ctx = createMockCtx()
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        return ServerCall(request, response, Parameters.Empty, app)
    }

    @Test
    fun `no Origin header skips CORS processing`() = runTest {
        val config = CorsConfig().apply { anyHost() }
        val middleware = CorsMiddleware(config)
        val call = createCall()

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `anyHost allows all origins`() = runTest {
        val config = CorsConfig().apply { anyHost() }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://example.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `disallowed origin returns 403 Forbidden`() = runTest {
        val config = CorsConfig().apply {
            allowedHosts.add("https://allowed.com")
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://evil.com"))

        middleware.beforeCall(call)

        assertTrue(call.response.isCommitted)
        assertEquals(HttpStatusCode.Forbidden, call.response.status())
    }

    @Test
    fun `allowed host passes validation`() = runTest {
        val config = CorsConfig().apply {
            allowedHosts.add("https://allowed.com")
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://allowed.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `origin predicate allows matching origins`() = runTest {
        val config = CorsConfig().apply {
            originPredicates.add { it.endsWith(".myapp.com") }
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://sub.myapp.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `origin predicate rejects non-matching origins with 403 Forbidden`() = runTest {
        val config = CorsConfig().apply {
            originPredicates.add { it.endsWith(".myapp.com") }
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://other.com"))

        middleware.beforeCall(call)

        assertTrue(call.response.isCommitted)
        assertEquals(HttpStatusCode.Forbidden, call.response.status())
    }

    @Test
    fun `OPTIONS preflight responds with CORS headers`() = runTest {
        val config = CorsConfig().apply {
            anyHost()
            anyMethod()
            allowHeader("X-Custom")
            maxAgeSeconds = 3600
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(
            method = HttpMethod.Options,
            headers = mapOf(
                HttpHeaders.Origin to "https://example.com",
                HttpHeaders.AccessControlRequestMethod to "POST",
            )
        )

        middleware.beforeCall(call)

        assertTrue(call.response.isCommitted)
    }

    @Test
    fun `credentials flag adds Allow-Credentials header`() = runTest {
        val config = CorsConfig().apply {
            anyHost()
            allowCredentials = true
            developmentMode = true
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://example.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `exposed headers are included in response`() = runTest {
        val config = CorsConfig().apply {
            anyHost()
            exposedHeaders.add("X-Custom-Header")
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://example.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `preflight with credentials adds Allow-Credentials`() = runTest {
        val config = CorsConfig().apply {
            anyHost()
            allowCredentials = true
            developmentMode = true
            anyMethod()
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(
            method = HttpMethod.Options,
            headers = mapOf(
                HttpHeaders.Origin to "https://example.com",
                HttpHeaders.AccessControlRequestMethod to "POST",
            )
        )

        middleware.beforeCall(call)

        assertTrue(call.response.isCommitted)
    }

    @Test
    fun `preflight with zero maxAge omits Max-Age header`() = runTest {
        val config = CorsConfig().apply {
            anyHost()
            maxAgeSeconds = 0
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(
            method = HttpMethod.Options,
            headers = mapOf(
                HttpHeaders.Origin to "https://example.com",
                HttpHeaders.AccessControlRequestMethod to "GET",
            )
        )

        middleware.beforeCall(call)

        assertTrue(call.response.isCommitted)
    }

    @Test
    fun `already committed response is skipped`() = runTest {
        val config = CorsConfig().apply { anyHost() }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://example.com"))

        // Pre-commit the response
        call.response.status(HttpStatusCode.OK)
        call.response.commit()

        middleware.beforeCall(call)

        // Should not throw or double-commit
        assertTrue(call.response.isCommitted)
    }

    @Test
    fun `Vary header added when not anyHost`() = runTest {
        val config = CorsConfig().apply {
            allowedHosts.add("https://specific.com")
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://specific.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    @Test
    fun `Vary header added with credentials even when anyHost`() = runTest {
        val config = CorsConfig().apply {
            anyHost()
            allowCredentials = true
            developmentMode = true
        }
        val middleware = CorsMiddleware(config)
        val call = createCall(headers = mapOf(HttpHeaders.Origin to "https://example.com"))

        middleware.beforeCall(call)

        assertFalse(call.response.isCommitted)
    }

    // --- CorsConfig tests ---

    @Test
    fun `anyMethod adds all standard methods`() {
        val config = CorsConfig()
        config.anyMethod()
        assertTrue(config.allowedMethods.contains(HttpMethod.Get))
        assertTrue(config.allowedMethods.contains(HttpMethod.Post))
        assertTrue(config.allowedMethods.contains(HttpMethod.Put))
        assertTrue(config.allowedMethods.contains(HttpMethod.Delete))
        assertTrue(config.allowedMethods.contains(HttpMethod.Patch))
        assertTrue(config.allowedMethods.contains(HttpMethod.Head))
        assertTrue(config.allowedMethods.contains(HttpMethod.Options))
    }

    @Test
    fun `anyHost sets flag`() {
        val config = CorsConfig()
        assertFalse(config.allowAnyHost)
        config.anyHost()
        assertTrue(config.allowAnyHost)
    }

    @Test
    fun `allowHeader adds to set`() {
        val config = CorsConfig()
        config.allowHeader("Authorization")
        config.allowHeader("X-Custom")
        assertTrue(config.allowedHeaders.contains("Authorization"))
        assertTrue(config.allowedHeaders.contains("X-Custom"))
    }

    @Test
    fun `defaults are correct`() {
        val config = CorsConfig()
        assertFalse(config.allowAnyHost)
        assertFalse(config.allowCredentials)
        assertEquals(86400L, config.maxAgeSeconds)
        assertTrue(config.allowedHosts.isEmpty())
        assertTrue(config.allowedMethods.isEmpty())
        assertTrue(config.allowedHeaders.isEmpty())
        assertTrue(config.exposedHeaders.isEmpty())
        assertTrue(config.originPredicates.isEmpty())
    }

    @Test
    fun `minimal CorsConfiguration round trips and preserves runtime defaults`() {
        val configuration = CorsConfiguration()
        val json = Json { encodeDefaults = false }
        val restored = json.decodeFromString(
            CorsConfiguration.serializer(),
            json.encodeToString(CorsConfiguration.serializer(), configuration),
        )
        val runtime = restored.toCorsConfig(developmentMode = false)

        assertEquals(configuration, restored)
        assertFalse(runtime.developmentMode)
        assertFalse(runtime.allowAnyHost)
        assertFalse(runtime.allowCredentials)
        assertTrue(runtime.allowedMethods.isEmpty())
    }

    @Test
    fun `complete CorsConfiguration maps every explicit setting`() {
        val configuration = CorsConfiguration(
            allowAnyHost = true,
            allowCredentials = true,
            anyMethod = true,
            maxAgeSeconds = 60,
            allowedHosts = listOf("https://example.test"),
            allowedMethods = listOf("GET", "PATCH"),
            allowedHeaders = listOf("Authorization"),
            exposedHeaders = listOf("X-Request-Id"),
        )
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString(
            CorsConfiguration.serializer(),
            json.encodeToString(CorsConfiguration.serializer(), configuration),
        )
        val runtime = restored.toCorsConfig(developmentMode = true)

        assertEquals(configuration, restored)
        assertTrue(runtime.developmentMode)
        assertTrue(runtime.allowAnyHost)
        assertTrue(runtime.allowCredentials)
        assertEquals(60, runtime.maxAgeSeconds)
        assertTrue(runtime.allowedMethods.containsAll(setOf(
            HttpMethod.Get,
            HttpMethod.Post,
            HttpMethod.Put,
            HttpMethod.Delete,
            HttpMethod.Patch,
            HttpMethod.Head,
            HttpMethod.Options,
        )))
        assertEquals(setOf("https://example.test"), runtime.allowedHosts)
        assertEquals(setOf("Authorization"), runtime.allowedHeaders)
        assertEquals(setOf("X-Request-Id"), runtime.exposedHeaders)
    }

    @Test
    fun `preflight rejects a method outside the configured set`() = runTest {
        val middleware = CorsMiddleware(CorsConfig().apply {
            anyHost()
            allowedMethods.add(HttpMethod.Get)
        })
        val call = createCall(
            method = HttpMethod.Options,
            headers = mapOf(
                HttpHeaders.Origin to "https://example.test",
                HttpHeaders.AccessControlRequestMethod to "DELETE",
            ),
        )

        middleware.beforeCall(call)

        assertEquals(HttpStatusCode.Forbidden, call.response.status())
    }

    @Test
    fun `preflight rejects a header outside the configured set`() = runTest {
        val middleware = CorsMiddleware(CorsConfig().apply {
            anyHost()
            allowedMethods.add(HttpMethod.Post)
            allowedHeaders.add("Content-Type")
        })
        val call = createCall(
            method = HttpMethod.Options,
            headers = mapOf(
                HttpHeaders.Origin to "https://example.test",
                HttpHeaders.AccessControlRequestMethod to "POST",
                HttpHeaders.AccessControlRequestHeaders to "Content-Type, X-Forbidden",
            ),
        )

        middleware.beforeCall(call)

        assertEquals(HttpStatusCode.Forbidden, call.response.status())
    }

    @Test
    fun `preflight accepts default method and headers case insensitively`() = runTest {
        val middleware = CorsMiddleware(CorsConfig().apply { anyHost() })
        val call = createCall(
            method = HttpMethod.Options,
            headers = mapOf(
                HttpHeaders.Origin to "https://example.test",
                HttpHeaders.AccessControlRequestMethod to "post",
                HttpHeaders.AccessControlRequestHeaders to "content-type, ACCEPT",
            ),
        )

        middleware.beforeCall(call)

        assertEquals(HttpStatusCode.NoContent, call.response.status())
    }
}
