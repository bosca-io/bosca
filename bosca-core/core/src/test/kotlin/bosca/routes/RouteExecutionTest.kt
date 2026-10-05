@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.routes

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.graphql.server.GraphQLException
import bosca.security.service.AuthenticationContext
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.sse.ServerSSESession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private class TestRoute<T>(
    private val resultSerializer: KSerializer<T>? = null,
    private val action: suspend (ServerCall) -> T?,
) : Route<T>() {
    override fun serializer(): KSerializer<T>? = resultSerializer

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): T? = action(call)
}

private class TestAPIRoute<T>(
    private val resultSerializer: KSerializer<T>? = null,
    private val action: suspend (ServerCall) -> T?,
) : APIRoute<T>() {
    override fun serializer(): KSerializer<T>? = resultSerializer

    override suspend fun execute(call: ServerCall, authenticationContext: AuthenticationContext): T? = action(call)
}

private class TestSSERoute(
    private val action: suspend () -> Unit,
) : SSERoute<Unit>() {
    override suspend fun execute(session: ServerSSESession, authenticationContext: AuthenticationContext) = action()
}

class RouteExecutionTest {

    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        pool = mockk()
        every { pool.connection() } answers { ConnectionManager(pool) }
        provides<ConnectionPool> { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private fun context(responses: MutableList<Any> = mutableListOf()): ChannelHandlerContext {
        val ctx = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val future = mockk<ChannelFuture>(relaxed = true)
        every { ctx.channel() } returns channel
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { ctx.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { ctx.writeAndFlush(capture(responses)) } returns future
        every { ctx.write(any()) } returns future
        return ctx
    }

    private fun call(responses: MutableList<Any> = mutableListOf()): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return ServerCall(request, ServerResponse(context(responses)), Parameters.Empty, BoscaApplication(config))
    }

    private fun status(responses: List<Any>): Int =
        (responses.single() as DefaultFullHttpResponse).status().code()

    @Test
    fun `route dispatches models statuses null unit and precommitted responses`() = runTest {
        var responses = mutableListOf<Any>()
        TestRoute<String> { "model" }.execute(call(responses))
        assertEquals(200, status(responses))

        responses = mutableListOf()
        TestRoute<HttpStatusCode> { HttpStatusCode.Accepted }.execute(call(responses))
        assertEquals(202, status(responses))

        responses = mutableListOf()
        TestRoute<String> { null }.execute(call(responses))
        assertEquals(404, status(responses))

        responses = mutableListOf()
        TestRoute<Unit> { Unit }.execute(call(responses))
        assertEquals(204, status(responses))

        responses = mutableListOf()
        TestRoute<Unit> { current ->
            current.response.status(HttpStatusCode.Accepted)
            Unit
        }.execute(call(responses))
        assertEquals(202, status(responses))

        responses = mutableListOf()
        TestRoute<String> { current ->
            current.respond(HttpStatusCode.Created, "already sent")
            "ignored"
        }.execute(call(responses))
        assertEquals(201, status(responses))
    }

    @Test
    fun `route maps expected failures and propagates cancellation`() = runTest {
        val failures = listOf(
            SecurityException("denied") to 401,
            GraphQLException("invalid") to 400,
            IllegalStateException("invalid") to 400,
            NoSuchElementException("missing") to 404,
            RuntimeException("broken") to 500,
        )
        failures.forEach { (failure, expected) ->
            val responses = mutableListOf<Any>()
            TestRoute<Unit> { throw failure }.execute(call(responses))
            assertEquals(expected, status(responses))
        }

        val cancellation = CancellationException("cancelled")
        val thrown = assertFailsWith<CancellationException> {
            TestRoute<Unit> { throw cancellation }.execute(call())
        }
        assertEquals("cancelled", thrown.message)
    }

    @Test
    fun `route does not overwrite a response committed before a failure`() = runTest {
        val responses = mutableListOf<Any>()
        TestRoute<Unit> { current ->
            current.respond(HttpStatusCode.Accepted, "sent")
            throw SecurityException("after commit")
        }.execute(call(responses))
        assertEquals(202, status(responses))
    }

    @Test
    fun `api route dispatches normal results and structured failures`() = runTest {
        val apiError = APIError(HttpStatusCode.BadRequest, IllegalArgumentException("invalid"))
        assertEquals(apiError, apiError)
        assertFalse(apiError.equals(Any()))
        assertFalse(apiError.equals(null))

        var responses = mutableListOf<Any>()
        TestAPIRoute<String> { "model" }.execute(call(responses))
        assertEquals(200, status(responses))

        responses = mutableListOf()
        TestAPIRoute<String> { null }.execute(call(responses))
        assertEquals(404, status(responses))

        responses = mutableListOf()
        TestAPIRoute<Unit> { Unit }.execute(call(responses))
        assertEquals(204, status(responses))

        responses = mutableListOf()
        TestAPIRoute<Unit> { current ->
            current.response.status(HttpStatusCode.Accepted)
            Unit
        }.execute(call(responses))
        assertEquals(202, status(responses))

        val failures = listOf(
            SecurityException("denied") to 401,
            IllegalStateException("invalid") to 400,
            NoSuchElementException("missing") to 404,
            RuntimeException("broken") to 500,
        )
        failures.forEach { (failure, expected) ->
            responses = mutableListOf()
            TestAPIRoute<Unit> { throw failure }.execute(call(responses))
            assertEquals(expected, status(responses))
        }

        val cancellation = CancellationException("cancelled")
        assertEquals("cancelled", assertFailsWith<CancellationException> {
            TestAPIRoute<Unit> { throw cancellation }.execute(call())
        }.message)
    }

    @Test
    fun `sse route maps failures before and after commitment and propagates cancellation`() = runTest {
        val failures = listOf(
            SecurityException("denied") to 401,
            IllegalStateException("invalid") to 400,
            NoSuchElementException("missing") to 404,
            RuntimeException("broken") to 500,
        )
        failures.forEach { (failure, expected) ->
            val responses = mutableListOf<Any>()
            val currentCall = call(responses)
            val session = mockk<ServerSSESession>(relaxed = true)
            every { session.call } returns currentCall
            TestSSERoute { throw failure }.execute(session)
            assertEquals(expected, status(responses))
        }

        failures.forEach { (failure, _) ->
            val currentCall = call()
            currentCall.response.markCommitted()
            val session = mockk<ServerSSESession>(relaxed = true)
            every { session.call } returns currentCall
            coEvery { session.send(any(), any(), any()) } returns Unit
            coEvery { session.close() } returns Unit
            TestSSERoute { throw failure }.execute(session)
            coVerify(exactly = 1) { session.send(data = any(), event = "error", id = null) }
            coVerify(exactly = 1) { session.close() }
        }

        val session = mockk<ServerSSESession>(relaxed = true)
        every { session.call } returns call()
        val cancellation = CancellationException("cancelled")
        assertEquals("cancelled", assertFailsWith<CancellationException> {
            TestSSERoute { throw cancellation }.execute(session)
        }.message)
    }
}
