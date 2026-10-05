package bosca.feeds.http

import bosca.service.annotation.ServiceImplementation
import java.io.IOException
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** OkHttp-backed [FeedHttpClient]. Runs on the bosca-runner (JVM) when the fetch job executes. */
@ServiceImplementation
class OkHttpFeedHttpClient : FeedHttpClient {

    private val client = OkHttpClient()

    override suspend fun get(url: String, headers: Map<String, String>): FeedHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body.string()
                        continuation.resume(
                            FeedHttpResponse(
                                status = response.code,
                                body = body,
                                etag = response.header("ETag"),
                                lastModified = response.headers.getInstant("Last-Modified")?.atOffset(ZoneOffset.UTC),
                            ),
                        )
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    } finally {
                        response.close()
                    }
                }
            })
        }
}
