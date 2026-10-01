package bosca.server.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Executes an OkHttp [Call] asynchronously using its [enqueue] callback API, suspending the
 * coroutine until the response is available. This avoids blocking any thread while waiting
 * for the HTTP response, unlike [Call.execute] which ties up a thread for the full duration.
 *
 * The call is automatically cancelled if the coroutine is cancelled.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // Use try-catch instead of isCancelled check to avoid TOCTOU race
            // where the coroutine could be cancelled between check and resume
            try {
                continuation.resumeWithException(e)
            } catch (_: IllegalStateException) {
                // Coroutine was already cancelled or resumed
            }
        }

        override fun onResponse(call: Call, response: Response) {
            // A cancelled continuation silently rejects the value rather than throwing, both when it
            // was cancelled before this resume and when cancellation lands while it is dispatched.
            // The response is closed in either case so its connection is not leaked.
            continuation.resume(response) { _, rejected, _ -> rejected.close() }
        }
    })
}
