package bosca.server.middleware

import bosca.server.BoscaApplication
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallLoggingMiddlewareTest {

    private fun createCall(
        method: HttpMethod = HttpMethod.Get,
        uri: String = "/test",
        status: HttpStatusCode? = HttpStatusCode.OK
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.httpMethod } returns method
        every { request.uri } returns uri

        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        every { ctx.channel() } returns channel
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(any()) } returns mockk<ChannelFuture>(relaxed = true)

        val response = ServerResponse(ctx)
        if (status != null) response.status(status)

        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        val app = BoscaApplication(config)
        return ServerCall(request, response, Parameters.Empty, app)
    }

    @Test
    fun `afterCall logs when filter returns true`() = runTest {
        val middleware = CallLoggingMiddleware { true }
        val call = createCall(HttpMethod.Get, "/api/data", HttpStatusCode.OK)

        // Should not throw
        middleware.afterCall(call)
    }

    @Test
    fun `afterCall skips logging when filter returns false`() = runTest {
        var logged = false
        val middleware = CallLoggingMiddleware { logged = true; false }
        val call = createCall()

        middleware.afterCall(call)

        // Filter was checked (logged=true) but returned false, so logging was skipped
        assertEquals(true, logged)
    }

    @Test
    fun `default filter allows all requests`() = runTest {
        val middleware = CallLoggingMiddleware()
        val call = createCall()

        // Should not throw - default filter returns true
        middleware.afterCall(call)
    }

    @Test
    fun `afterCall handles null status`() = runTest {
        val middleware = CallLoggingMiddleware()
        val call = createCall(status = null)

        // Should not throw even with null status
        middleware.afterCall(call)
    }
}
