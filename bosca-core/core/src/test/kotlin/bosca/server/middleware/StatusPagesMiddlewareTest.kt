package bosca.server.middleware

import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.ServerResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusPagesMiddlewareTest {

    @Test
    fun `exception handler is registered and invoked`() = runBlocking {
        var handledMessage = ""
        val middleware = StatusPagesMiddleware.Builder().apply {
            exception<IllegalArgumentException> { _, cause ->
                handledMessage = cause.message ?: ""
            }
        }.build()

        val call = mockServerCall(committed = false)
        middleware.onException(call, IllegalArgumentException("test error"))
        assertEquals("test error", handledMessage)
    }

    @Test
    fun `exception handler matches subclass`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            exception<RuntimeException> { _, _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = false)
        middleware.onException(call, IllegalStateException("subclass"))
        assertTrue(handled)
    }

    @Test
    fun `exception handler not invoked for unregistered type`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            exception<IllegalArgumentException> { _, _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = false)
        middleware.onException(call, NullPointerException("different"))
        assertTrue(!handled)
    }

    @Test
    fun `default exception handler invoked when no specific handler matches`() = runBlocking {
        var defaultHandled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            exception<IllegalArgumentException> { _, _ -> }
            defaultException { _, _ ->
                defaultHandled = true
            }
        }.build()

        val call = mockServerCall(committed = false)
        middleware.onException(call, NullPointerException("no specific handler"))
        assertTrue(defaultHandled)
    }

    @Test
    fun `default exception handler not invoked when specific handler matches`() = runBlocking {
        var specificHandled = false
        var defaultHandled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            exception<IllegalArgumentException> { _, _ ->
                specificHandled = true
            }
            defaultException { _, _ ->
                defaultHandled = true
            }
        }.build()

        val call = mockServerCall(committed = false)
        middleware.onException(call, IllegalArgumentException("specific"))
        assertTrue(specificHandled)
        assertTrue(!defaultHandled)
    }

    @Test
    fun `exception handler skipped when response is committed`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            exception<RuntimeException> { _, _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = true)
        middleware.onException(call, RuntimeException("committed"))
        assertTrue(!handled)
    }

    @Test
    fun `status handler is registered and invoked`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            status(HttpStatusCode.NotFound) { _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = false, status = HttpStatusCode.NotFound)
        middleware.afterCall(call)
        assertTrue(handled)
    }

    @Test
    fun `status handler not invoked for different status`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            status(HttpStatusCode.NotFound) { _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = false, status = HttpStatusCode.OK)
        middleware.afterCall(call)
        assertTrue(!handled)
    }

    @Test
    fun `status handler skipped when response is committed`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            status(HttpStatusCode.NotFound) { _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = true, status = HttpStatusCode.NotFound)
        middleware.afterCall(call)
        assertTrue(!handled)
    }

    @Test
    fun `status handler skipped when no status is set`() = runBlocking {
        var handled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            status(HttpStatusCode.NotFound) { _ ->
                handled = true
            }
        }.build()

        val call = mockServerCall(committed = false, status = null)
        middleware.afterCall(call)
        assertTrue(!handled)
    }

    @Test
    fun `multiple status handlers for different codes`() = runBlocking {
        var notFoundHandled = false
        var serverErrorHandled = false
        val middleware = StatusPagesMiddleware.Builder().apply {
            status(HttpStatusCode.NotFound) { _ ->
                notFoundHandled = true
            }
            status(HttpStatusCode.InternalServerError) { _ ->
                serverErrorHandled = true
            }
        }.build()

        val call404 = mockServerCall(committed = false, status = HttpStatusCode.NotFound)
        middleware.afterCall(call404)
        assertTrue(notFoundHandled)
        assertTrue(!serverErrorHandled)

        val call500 = mockServerCall(committed = false, status = HttpStatusCode.InternalServerError)
        middleware.afterCall(call500)
        assertTrue(serverErrorHandled)
    }

    @Test
    fun `builder produces middleware with no handlers`() = runBlocking {
        val middleware = StatusPagesMiddleware.Builder().build()
        val call = mockServerCall(committed = false)
        // Should not throw
        middleware.onException(call, RuntimeException("test"))
        middleware.afterCall(call)
    }

    private fun mockServerCall(
        committed: Boolean,
        status: HttpStatusCode? = null
    ): ServerCall {
        val response = mockk<ServerResponse>()
        every { response.isCommitted } returns committed
        every { response.status() } returns status
        val call = mockk<ServerCall>()
        every { call.response } returns response
        return call
    }
}
