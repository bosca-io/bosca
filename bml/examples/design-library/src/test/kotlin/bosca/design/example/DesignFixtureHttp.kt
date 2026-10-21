package bosca.design.example

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Send a fixture request, including its complete response body, within one deadline. */
internal fun requestDesignFixture(
    client: HttpClient,
    request: HttpRequest,
    timeout: Duration = Duration.ofSeconds(10),
): HttpResponse<String> {
    val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    try {
        return response.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
    } catch (failure: TimeoutException) {
        throw AssertionError(
            "Timed out after ${timeout.toMillis()}ms waiting for complete HTTP response from ${request.method()} ${request.uri()}",
            failure,
        )
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } finally {
        if (!response.isDone) response.cancel(true)
    }
}
