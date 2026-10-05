package bosca.server.middleware

import bosca.server.BoscaApplication
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultHeadersMiddlewareTest {

    private fun createCall(): Pair<ServerCall, ChannelHandlerContext> {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { ctx.channel() } returns channel
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(any()) } returns mockk<ChannelFuture>(relaxed = true)

        val request = mockk<ServerRequest>(relaxed = true)
        val response = ServerResponse(ctx)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        return ServerCall(request, response, Parameters.Empty, app) to ctx
    }

    @Test
    fun `adds default headers to response`() = runTest {
        val headers = mapOf(
            "X-Frame-Options" to "DENY",
            "X-Content-Type-Options" to "nosniff"
        )
        val middleware = DefaultHeadersMiddleware(headers)
        val (call, ctx) = createCall()

        middleware.beforeCall(call)

        // Verify headers were added by committing and inspecting
        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        call.response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals("DENY", sent.headers().get("X-Frame-Options"))
        assertEquals("nosniff", sent.headers().get("X-Content-Type-Options"))
    }

    @Test
    fun `empty headers map adds nothing`() = runTest {
        val middleware = DefaultHeadersMiddleware(emptyMap())
        val (call, _) = createCall()

        middleware.beforeCall(call)
        // Should not throw
    }

    @Test
    fun `multiple headers all applied`() = runTest {
        val headers = mapOf(
            "Server" to "Bosca",
            "X-Request-Id" to "abc-123",
            "Cache-Control" to "no-store"
        )
        val middleware = DefaultHeadersMiddleware(headers)
        val (call, ctx) = createCall()

        middleware.beforeCall(call)

        val captured = slot<Any>()
        every { ctx.writeAndFlush(capture(captured)) } returns mockk(relaxed = true)
        call.response.commit()

        val sent = captured.captured as DefaultFullHttpResponse
        assertEquals("Bosca", sent.headers().get("Server"))
        assertEquals("abc-123", sent.headers().get("X-Request-Id"))
        assertEquals("no-store", sent.headers().get("Cache-Control"))
    }
}
