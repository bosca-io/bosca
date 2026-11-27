package bosca.atlas.server

import java.net.BindException
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.channels.ServerSocketChannel
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A localhost origin must not select an unrelated listener on either address family. */
internal fun atlasFixturePort(candidate: () -> Int = { 0 }): Int {
    repeat(20) {
        try {
            ServerSocketChannel.open(StandardProtocolFamily.INET).use { ipv4 ->
                ipv4.bind(InetSocketAddress("127.0.0.1", candidate()))
                val port = (ipv4.localAddress as InetSocketAddress).port
                ServerSocketChannel.open(StandardProtocolFamily.INET6).use { ipv6 ->
                    ipv6.bind(InetSocketAddress("::1", port))
                    return port
                }
            }
        } catch (_: BindException) {
            // Retry while retaining the same localhost origin semantics for clients and browsers.
        }
    }
    error("No available IPv4/IPv6 loopback port for the Atlas fixture")
}

/** Bound the complete exchange: a request timeout alone can expire before the body is consumed. */
internal fun atlasFixtureResponse(client: HttpClient, request: HttpRequest, timeout: Duration = Duration.ofSeconds(15)): HttpResponse<String> {
    val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    return try {
        pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        pending.cancel(true)
        throw HttpTimeoutException("Timed out reading ${request.uri()}").apply { initCause(error) }
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}
