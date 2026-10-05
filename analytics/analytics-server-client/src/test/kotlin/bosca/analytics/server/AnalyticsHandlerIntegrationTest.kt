package bosca.analytics.server

import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Principal
import bosca.server.BoscaApplication
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import bosca.server.config.ApplicationConfig
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.netty.NettyHttpHandler
import bosca.server.routing.AuthConfig
import io.mockk.coVerify
import io.mockk.mockk
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpServerKeepAliveHandler
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.stream.ChunkedWriteHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsHandlerIntegrationTest {
    @Test
    fun `Netty handlers inherit authenticated context while requests and exception callbacks stay isolated`() = runTest {
        val app = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        val client = mockk<ServerAnalyticsClient>(relaxed = true)
        app.install(AnalyticsMiddleware(client, "server"))
        val principal = AuthenticatedPrincipal(Principal(), emptyList())
        app.installAuth(object : AuthMiddleware {
            override suspend fun authenticate(call: ServerCall, authConfig: AuthConfig?) {
                @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
                call.authenticationContext.principal("test", principal)
            }
        })
        val after = mutableListOf<AnalyticsContext>()
        app.install(object : CallMiddleware {
            override suspend fun beforeCall(call: ServerCall) {
                if (call.request.path == "/rejected") call.respond(HttpStatusCode.Forbidden, "rejected")
            }
            override suspend fun afterCall(call: ServerCall) { after += analyticsContext() }
        })
        val gate = CompletableDeferred<Unit>()
        val observed = mutableMapOf<String, AnalyticsContext>()
        app.routing {
            authenticate("test") {
                get("/context") {
                    val key = call.request.headers["X-BA-Session-ID"] ?: "missing"
                    gate.await()
                    observed[key] = coroutineScope { async { analyticsContext() }.await() }
                    call.respond(HttpStatusCode.OK, "ok")
                }
                get("/error") { throw IllegalStateException("handler failed") }
                get("/rejected") { error("must not execute") }
                sse("/events") { observed["sse"] = analyticsContext() }
                webSocket("/socket") { observed["ws"] = async { analyticsContext() }.await() }
            }
        }
        app.freezeMiddleware()
        val handler = NettyHttpHandler(app, backgroundScope, 1_000_000)
        val channels = mutableListOf<EmbeddedChannel>()
        fun request(path: String, session: String?, upgrade: Boolean = false): EmbeddedChannel {
            val channel = EmbeddedChannel(HttpServerCodec(), HttpServerKeepAliveHandler(), ChunkedWriteHandler(), handler)
            channels += channel
            val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)
            request.headers().set("Host", "localhost")
            request.headers().set("X-App-ID", "client")
            request.headers().set("X-App-Version", "42")
            request.headers().set("X-Installation-ID", "installation")
            if (session != null) request.headers().set("X-BA-Session-ID", session)
            if (path == "/events") request.headers().set("Accept", "text/event-stream")
            if (upgrade) {
                request.headers().set("Upgrade", "websocket")
                request.headers().set("Connection", "Upgrade")
                request.headers().set("Sec-WebSocket-Version", "13")
                request.headers().set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            }
            channel.writeInbound(request)
            return channel
        }
        try {
            request("/context", "a")
            request("/context", "b")
            request("/context", null)
            runCurrent()
            assertTrue(observed.isEmpty())
            gate.complete(Unit)
            request("/error", "error-session")
            request("/rejected", "rejected-session")
            request("/events", "sse-session")
            request("/socket", "ws-session", upgrade = true)
            repeat(5) {
                runCurrent()
                channels.forEach { it.runPendingTasks() }
            }
            for ((key, session) in mapOf("a" to "a", "b" to "b", "missing" to null,
                "sse" to "sse-session", "ws" to "ws-session")) {
                assertEquals(AnalyticsContext("client", "42", "installation", session, principal.id.toString()), observed[key], key)
            }
            coVerify(exactly = 1) {
                client.captureException(any(), any(), "server", "error-session", principal.id.toString(), any())
            }
            assertEquals(7, after.size)
            assertTrue(after.all { it == AnalyticsContext() })
            assertEquals(AnalyticsContext(), analyticsContext())
        } finally {
            channels.forEach { it.finishAndReleaseAll() }
            app.shutdown()
        }
    }
}
