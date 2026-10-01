package bosca.server.middleware

import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

class SessionMiddlewareTest {

    private val sessionWriter = mockk<SessionWriter>(relaxed = true)
    private val middleware = SessionMiddleware(sessionWriter)

    private fun createCall(): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        val response = mockk<ServerResponse>(relaxed = true)
        val app = mockk<BoscaApplication>(relaxed = true)
        return ServerCall(request, response, application = app)
    }

    @Test
    fun `onBeforeWrite writes session when session is set`() {
        val call = createCall()
        val sessionData = "test-session-data"
        call.sessions.set(sessionData)

        middleware.onBeforeWrite(call)

        verify { sessionWriter.writeSession(call, sessionData) }
    }

    @Test
    fun `onBeforeWrite clears session when session is cleared`() {
        val call = createCall()
        call.sessions.set("something")
        call.sessions.clear()

        middleware.onBeforeWrite(call)

        verify { sessionWriter.clearSession(call) }
    }

    @Test
    fun `onBeforeWrite does nothing when session is not modified`() {
        val call = createCall()

        middleware.onBeforeWrite(call)

        verify(exactly = 0) { sessionWriter.writeSession(any(), any()) }
        verify(exactly = 0) { sessionWriter.clearSession(any()) }
    }
}
