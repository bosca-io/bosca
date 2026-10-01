package bosca.server

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {

    private fun createMockCtx(): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.writeAndFlush(any()) } returns future
        return ctx
    }

    private fun createCall(): ServerCall {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        return ServerCall(request, response, Parameters.Empty, app)
    }

    // --- SessionManager basic operations ---

    @Test
    fun `isModified starts as false`() {
        val call = createCall()
        assertFalse(call.sessions.isModified)
    }

    @Test
    fun `get returns null when no session set`() {
        val call = createCall()
        assertNull(call.sessions.get<String>())
    }

    @Test
    fun `raw returns null when no session set`() {
        val call = createCall()
        assertNull(call.sessions.raw())
    }

    @Test
    fun `set stores session and marks modified`() {
        val call = createCall()
        call.sessions.set("my-session-token")
        assertTrue(call.sessions.isModified)
        assertEquals("my-session-token", call.sessions.get<String>())
    }

    @Test
    fun `set updates raw value`() {
        val call = createCall()
        call.sessions.set("token-123")
        assertEquals("token-123", call.sessions.raw())
    }

    @Test
    fun `set overwrites previous session`() {
        val call = createCall()
        call.sessions.set("first")
        call.sessions.set("second")
        assertEquals("second", call.sessions.get<String>())
    }

    @Test
    fun `clear nulls the session and marks modified`() {
        val call = createCall()
        call.sessions.set("token")
        call.sessions.clear()
        assertTrue(call.sessions.isModified)
        assertNull(call.sessions.get<String>())
        assertNull(call.sessions.raw())
    }

    @Test
    fun `clear on empty session still marks modified`() {
        val call = createCall()
        call.sessions.clear()
        assertTrue(call.sessions.isModified)
    }

    @Test
    fun `get returns the stored value with matching type`() {
        val call = createCall()
        call.sessions.set(42)
        assertEquals(42, call.sessions.get<Int>())
    }

    @Test
    fun `session accepts any type`() {
        data class UserSession(val userId: String, val role: String)

        val call = createCall()
        val session = UserSession("user-1", "admin")
        call.sessions.set(session)
        assertEquals(session, call.sessions.get<UserSession>())
    }

    // --- Cookie lifecycle: set session → commit → Set-Cookie header present ---

    @Test
    fun `session cookie appears in response after commit`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(request, response, Parameters.Empty, app)

        // Simulate middleware setting a session cookie
        response.cookies.append(
            name = "_bat",
            value = "jwt-token-value",
            maxAge = 3600,
            domain = "example.com",
            path = "/",
        )
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val setCookieHeaders = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertEquals(1, setCookieHeaders.size)
        val cookie = setCookieHeaders[0]
        assertTrue(cookie.contains("_bat=jwt-token-value"))
        assertTrue(cookie.contains("Max-Age=3600"))
        assertTrue(cookie.contains("Domain=example.com"))
        assertTrue(cookie.contains("Path=/"))
    }

    @Test
    fun `clearing session cookie sets maxAge to zero`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(request, response, Parameters.Empty, app)

        // Simulate clearing a session cookie (as clearSessionCookie does)
        response.cookies.append(
            Cookie(
                name = "_bat",
                value = "",
                maxAge = 0,
                domain = "example.com",
                path = "/",
            )
        )
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val setCookieHeaders = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertEquals(1, setCookieHeaders.size)
        val cookie = setCookieHeaders[0]
        assertTrue(cookie.contains("_bat="))
        assertTrue(cookie.contains("Max-Age=0"))
    }

    @Test
    fun `multiple cookies appear as separate Set-Cookie headers`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val response = ServerResponse(ctx)
        response.cookies.append("session", "abc")
        response.cookies.append("theme", "dark")
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val headers = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertEquals(2, headers.size)
        assertTrue(headers.any { it.contains("session=abc") })
        assertTrue(headers.any { it.contains("theme=dark") })
    }

    @Test
    fun `no cookies results in no Set-Cookie headers`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val response = ServerResponse(ctx)
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val headers = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertTrue(headers.isEmpty())
    }

    // --- Request cookie reading ---

    @Test
    fun `request cookies are accessible from ServerCall`() {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.cookies } returns RequestCookies("_bat=my-jwt-token; theme=dark")
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(request, response, Parameters.Empty, app)

        assertEquals("my-jwt-token", call.request.cookies["_bat"])
        assertEquals("dark", call.request.cookies["theme"])
        assertNull(call.request.cookies["missing"])
    }

    @Test
    fun `request with no cookie header returns null for all lookups`() {
        val ctx = createMockCtx()
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.cookies } returns RequestCookies(null)
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(request, response, Parameters.Empty, app)

        assertNull(call.request.cookies["_bat"])
    }

    // --- Session + cookie round-trip ---

    @Test
    fun `set session then write cookie then commit produces correct header`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(request, response, Parameters.Empty, app)

        // Simulate what a login handler + afterCall middleware would do:
        // 1. Handler sets session
        call.sessions.set("jwt-token-xyz")
        assertTrue(call.sessions.isModified)

        // 2. afterCall middleware writes cookie based on session state
        val session = call.sessions.raw() as String
        response.cookies.append(
            Cookie(
                name = "_bat",
                value = session,
                maxAge = 86400,
                path = "/",
                domain = "app.example.com",
            )
        )

        // 3. Response is committed
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val headers = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertEquals(1, headers.size)
        assertTrue(headers[0].contains("_bat=jwt-token-xyz"))
        assertTrue(headers[0].contains("Max-Age=86400"))
    }

    @Test
    fun `clear session then write empty cookie produces expired header`() {
        val ctx = createMockCtx()
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)

        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val app = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(request, response, Parameters.Empty, app)

        // Simulate a logout flow
        call.sessions.set("old-token")
        call.sessions.clear()
        assertTrue(call.sessions.isModified)
        assertNull(call.sessions.raw())

        // afterCall middleware sees clear → writes expired cookie
        response.cookies.append(
            Cookie(
                name = "_bat",
                value = "",
                maxAge = 0,
                path = "/",
                domain = "app.example.com",
            )
        )
        response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        val headers = sent.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertEquals(1, headers.size)
        assertTrue(headers[0].contains("_bat="))
        assertTrue(headers[0].contains("Max-Age=0"))
    }

    @Test
    fun `respondStreaming also includes Set-Cookie headers`() = runTest {
        val ctx = createMockCtx()
        val written = mutableListOf<Any>()
        every { ctx.write(capture(written)) } returns mockk(relaxed = true)
        every { ctx.writeAndFlush(capture(written)) } returns mockk(relaxed = true)

        val response = ServerResponse(ctx)
        response.cookies.append(
            Cookie(name = "_bat", value = "stream-token", maxAge = 3600, path = "/")
        )

        response.respondStreaming(ContentType.Application.Json) { stream ->
            stream.write("{}".toByteArray())
        }

        // The first write is the response headers (DefaultHttpResponse)
        val headerResponse = written[0] as io.netty.handler.codec.http.DefaultHttpResponse
        val setCookie = headerResponse.headers().getAll(HttpHeaderNames.SET_COOKIE)
        assertEquals(1, setCookie.size)
        assertTrue(setCookie[0].contains("_bat=stream-token"))
    }
}
