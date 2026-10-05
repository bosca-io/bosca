@file:OptIn(InternalDI::class)

package bosca.server.middleware

import bosca.counter.Counter
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** [ResponseCounterMiddleware]: increments the distributed counter per response, keyed by status class + minute. */
class ResponseCounterMiddlewareTest {

    private val counter = mockk<Counter>(relaxed = true)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Counter> { counter }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    /** A call whose response carries [status] (null = never set → treated as 200). */
    private fun call(status: HttpStatusCode? = HttpStatusCode.OK): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        every { ctx.channel() } returns mockk<Channel>(relaxed = true)
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(any()) } returns mockk<ChannelFuture>(relaxed = true)
        val response = ServerResponse(ctx)
        if (status != null) response.status(status)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return ServerCall(request, response, Parameters.Empty, BoscaApplication(config))
    }

    // Fixed clock: 6042s → epoch-minute 100.
    private fun middleware(filter: (ServerCall) -> Boolean = { true }) =
        ResponseCounterMiddleware("web", nowEpochSeconds = { 6042L }, filter = filter)

    @Test
    fun `counts a 2xx into the service minute bucket`() = runTest {
        middleware().afterCall(call(HttpStatusCode.OK))
        coVerify(exactly = 1) { counter.increment("http.web.2xx.100") }
    }

    @Test
    fun `maps 4xx and 5xx to their class buckets`() = runTest {
        middleware().afterCall(call(HttpStatusCode(404, "Not Found")))
        middleware().afterCall(call(HttpStatusCode(500, "Internal Server Error")))
        coVerify(exactly = 1) { counter.increment("http.web.4xx.100") }
        coVerify(exactly = 1) { counter.increment("http.web.5xx.100") }
    }

    @Test
    fun `a response with no explicit status counts as 2xx`() = runTest {
        middleware().afterCall(call(status = null))
        coVerify(exactly = 1) { counter.increment("http.web.2xx.100") }
    }

    @Test
    fun `the filter excludes a call from counting`() = runTest {
        middleware(filter = { false }).afterCall(call())
        coVerify(exactly = 0) { counter.increment(any()) }
    }

    @Test
    fun `resolved counter is reused across calls`() = runTest {
        val middleware = middleware()

        middleware.afterCall(call())
        middleware.afterCall(call())

        coVerify(exactly = 2) { counter.increment("http.web.2xx.100") }
    }
}
