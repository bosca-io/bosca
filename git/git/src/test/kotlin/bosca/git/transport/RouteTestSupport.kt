@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.transport

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.routes.Route
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.service.AuthenticationProviders
import bosca.server.ContentType
import bosca.server.HttpStatusCode
import bosca.server.Parameters
import bosca.server.RequestHeaders
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.StreamingResponse
import bosca.server.auth.CallAuthenticationContext
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Test harness that drives a [Route] through its PUBLIC `execute(call)` entry —
 * the same path the Netty router uses — with a fully mocked [ServerCall] and the
 * DI providers the route wrapper resolves (connection pool, request cache,
 * authentication providers). Responses (status, bytes, headers, streamed body)
 * are captured in [RecordedResponse] for assertion.
 */
class RecordedResponse {
    var status: HttpStatusCode? = null
    var bytes: ByteArray? = null
    var contentType: ContentType? = null
    val headers = mutableMapOf<String, String>()
    val streamed = ByteArrayOutputStream()
    var committed = false

    val bodyText: String get() = bytes?.toString(Charsets.UTF_8) ?: streamed.toByteArray().toString(Charsets.UTF_8)
}

/** Registers the DI providers [Route.execute]'s wrapper resolves. Call once per test (after ProviderRegistry.clear()). */
fun registerRouteProviders() {
    provides<ConnectionPool>(singleton = true) { mockk(relaxed = true) }
    provides<CacheManager>(singleton = true) { mockk(relaxed = true) }
    provides<RequestCacheSerializer>(singleton = true) { mockk(relaxed = true) }
    provides<AuthenticationProviders>(singleton = true) { AuthenticationProviders(arrayOf()) }
}

/**
 * Builds a mocked [ServerCall] whose responses are captured in the returned
 * [RecordedResponse]. Requests are anonymous by default; pass [principal] to
 * authenticate (it is returned by the auth context's any-principal lookup).
 */
fun recordedCall(
    pathParameters: Map<String, String> = emptyMap(),
    queryParameters: Map<String, String> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    body: ByteArray = ByteArray(0),
    principal: AuthenticatedPrincipal? = null,
): Pair<ServerCall, RecordedResponse> {
    val recorded = RecordedResponse()

    val requestHeaders = mockk<RequestHeaders>(relaxed = true)
    every { requestHeaders[any()] } answers { headers[firstArg()] }

    val request = mockk<ServerRequest>(relaxed = true)
    every { request.queryParameters } returns Parameters(queryParameters.mapValues { listOf(it.value) })
    every { request.headers } returns requestHeaders
    every { request.header(any()) } answers { headers[firstArg()] }
    coEvery { request.bodyStreamTo(any()) } coAnswers {
        firstArg<java.io.OutputStream>().write(body)
        body.size.toLong()
    }
    coEvery { request.bodyStreamTo(any(), any()) } coAnswers {
        firstArg<java.io.OutputStream>().write(body)
        body.size.toLong()
    }
    coEvery { request.bodyInputStream() } answers { ByteArrayInputStream(body) }
    coEvery { request.bodyText() } returns body.toString(Charsets.UTF_8)
    coEvery { request.bodyBytes() } returns body

    // The response mock also backs ServerCall's INLINE reified respond(status, message)
    // overload: inline bodies execute at the call site (they cannot be stubbed), and they
    // delegate to these non-inline ServerResponse members — so recording happens here.
    val response = mockk<ServerResponse>(relaxed = true)
    every { response.isCommitted } answers { recorded.committed }
    every { response.status() } answers { recorded.status }
    every { response.header(any(), any()) } answers {
        recorded.headers[invocation.args[0] as String] = invocation.args[1] as String
    }
    every { response.status(any<HttpStatusCode>()) } answers {
        recorded.status = firstArg()
    }
    every { response.commit(any()) } answers { recorded.committed = true }
    every { response.respond(any()) } answers {
        if (!recorded.committed) { recorded.status = firstArg(); recorded.committed = true }
    }
    every { response.respondText(any(), any(), any()) } answers {
        if (!recorded.committed) {
            recorded.bytes = firstArg<String>().toByteArray()
            recorded.status = thirdArg()
            recorded.committed = true
        }
    }
    every { response.respondBytes(any(), any(), any()) } answers {
        recorded.bytes = firstArg()
        recorded.contentType = secondArg()
        recorded.status = thirdArg()
        recorded.committed = true
    }

    val authContext = mockk<CallAuthenticationContext>(relaxed = true)
    every { authContext.anyPrincipal() } returns principal
    every { authContext.principal(any()) } returns principal

    val streaming = mockk<StreamingResponse>(relaxed = true)
    coEvery { streaming.copyFrom(any(), any()) } coAnswers {
        firstArg<java.io.InputStream>().copyTo(recorded.streamed)
    }
    coEvery { streaming.copyFrom(any(), any(), any()) } coAnswers {
        firstArg<java.io.InputStream>().copyTo(recorded.streamed)
    }
    coEvery { streaming.outputStream() } returns recorded.streamed
    coEvery { streaming.write(any(), any(), any()) } coAnswers {
        recorded.streamed.write(firstArg<ByteArray>(), secondArg(), thirdArg())
    }

    val call = mockk<ServerCall>(relaxed = true)
    every { call.pathParameters } returns Parameters(pathParameters.mapValues { listOf(it.value) })
    every { call.request } returns request
    every { call.response } returns response
    every { call.authenticationContext } returns authContext
    // NOTE: the two-arg respond(status, message) is a `suspend inline reified` function and
    // MUST NOT be stubbed — its inlined body would execute inside the mockk recorder and
    // corrupt the other stubs. It is captured via the ServerResponse stubs above instead.
    every { call.respond(any<HttpStatusCode>()) } answers {
        if (!recorded.committed) { recorded.status = firstArg(); recorded.committed = true }
    }
    every { call.respondBytes(any(), any(), any()) } answers {
        recorded.bytes = firstArg()
        recorded.contentType = secondArg()
        recorded.status = thirdArg()
        recorded.committed = true
    }
    every { call.respondRedirect(any(), any()) } answers {
        recorded.status = if (secondArg()) HttpStatusCode.MovedPermanently else HttpStatusCode.Found
        recorded.headers["Location"] = firstArg()
        recorded.committed = true
    }
    coEvery { call.respondStreaming(any(), any(), any()) } coAnswers {
        recorded.contentType = firstArg()
        recorded.status = secondArg()
        recorded.committed = true
        thirdArg<suspend (StreamingResponse) -> Unit>().invoke(streaming)
    }
    coEvery { call.respondStreaming(any(), any(), any(), any()) } coAnswers {
        recorded.contentType = firstArg()
        recorded.status = secondArg()
        recorded.committed = true
        arg<suspend (StreamingResponse) -> Unit>(3).invoke(streaming)
    }

    return call to recorded
}

/** Runs [route] through its public entry (router path) against the given call. */
suspend fun runRoute(route: Route<*>, call: ServerCall) {
    route.execute(call)
}

/** Clears DI state between tests. */
fun clearRouteProviders() {
    ProviderRegistry.clear()
}

/** A [ByteArrayInputStream] helper kept for symmetry with request bodies. */
fun bytes(vararg content: String): ByteArray = content.joinToString("").toByteArray()
