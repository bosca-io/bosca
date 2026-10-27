package bosca.design.example

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Send a fixture request, including its complete response body, within one deadline. */
internal fun requestDesignFixture(
    client: HttpClient,
    request: HttpRequest,
    timeout: Duration = Duration.ofSeconds(10),
): HttpResponse<String> {
    val headers = AtomicReference<HttpResponse.ResponseInfo>()
    val bodyBytes = AtomicLong()
    val handler = HttpResponse.BodyHandler<String> { info ->
        headers.set(info)
        val body = HttpResponse.BodyHandlers.ofString().apply(info)
        object : HttpResponse.BodySubscriber<String> by body {
            override fun onNext(buffers: List<ByteBuffer>) {
                bodyBytes.addAndGet(buffers.sumOf { it.remaining().toLong() })
                body.onNext(buffers)
            }
        }
    }
    fun progress(): String = headers.get()?.let {
        "status=${it.statusCode()} protocol=${it.version()} " +
            "contentLength=${it.headers().firstValue("content-length").orElse("unspecified")} bodyBytes=${bodyBytes.get()}"
    } ?: "response headers not received"
    val response = client.sendAsync(request, handler)
    try {
        return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (failure: TimeoutException) {
        throw AssertionError(
            "Timed out after ${timeout.toMillis()}ms waiting for complete HTTP response from " +
                "${request.method()} ${request.uri()}: ${progress()}",
            failure,
        )
    } catch (failure: ExecutionException) {
        val cause = failure.cause ?: failure
        cause.addSuppressed(IllegalStateException("HTTP progress for ${request.method()} ${request.uri()}: ${progress()}"))
        throw cause
    } finally {
        if (!response.isDone) response.cancel(true)
    }
}
