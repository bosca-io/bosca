package bosca.server.middleware

import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HandlerMiddlewareTest {
    @Test
    fun `only handler middleware wraps execution and unwinds in registration order`() = runTest {
        val app = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        val steps = mutableListOf<String>()
        app.install(object : CallMiddleware {})
        app.install(object : CallMiddleware, HandlerMiddleware {
            override suspend fun onHandler(call: ServerCall, next: suspend () -> Unit) {
                steps += "outer enter"
                try { next() } finally { steps += "outer exit" }
            }
        })
        app.installHandler { _, next ->
            steps += "inner enter"
            try { next() } finally { steps += "inner exit" }
        }
        app.freezeMiddleware()
        assertEquals(2, app.middleware.size)
        assertEquals(2, app.handlerMiddleware.size)
        try {
            for (failure in listOf(null, IllegalStateException("failure"), CancellationException("cancel"))) {
                steps.clear()
                val handler: suspend () -> Unit = {
                    steps += "handler"
                    if (failure != null) throw failure
                }
                if (failure == null) app.onHandler(mockk(), handler)
                else {
                    val thrown = assertFailsWith<Exception> { app.onHandler(mockk(), handler) }
                    assertEquals(failure, thrown)
                }
                assertEquals(listOf("outer enter", "inner enter", "handler", "inner exit", "outer exit"), steps)
            }
        } finally { app.shutdown() }
    }
}
