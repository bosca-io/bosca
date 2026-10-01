package bosca.workops.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

/**
 * The single HTTP health probe (2xx = healthy) behind [EnvironmentService.probeHealth] callers —
 * the wait-healthy job and the CI verify-deployment gate observe health identically.
 */
object HttpHealthProbe {

    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(5))
        .readTimeout(Duration.ofSeconds(10))
        .build()
}
