package bosca.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class Client {

    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofMinutes(3))
        .readTimeout(Duration.ofMinutes(3))
        .writeTimeout(Duration.ofMinutes(3))
        .connectTimeout(Duration.ofSeconds(10))
        .dispatcher(Dispatcher().apply {
            maxRequests = 1024
            maxRequestsPerHost = 10
        })
        .connectionPool(
            ConnectionPool(
                maxIdleConnections = 5,
                keepAliveDuration = 1,
                timeUnit = TimeUnit.MINUTES
            )
        )
        .build()

    suspend fun download(url: String, extension: String): File = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("tmp", ".$extension")
        val response = http.newCall(Request.Builder().url(url).build()).executeAsync()
        try {
            if (response.isSuccessful.not()) throw IOException("Failed to download file: ${response.code} ${response.message} - $url")
            response.body.use { body ->
                body.byteStream().use { inputStream ->
                    tmp.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } finally {
            response.closeQuietly()
        }
        tmp
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun Call.executeAsync() = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response) { _, _, _ ->
                        response.closeQuietly()
                    }
                }
            },
        )
    }
}