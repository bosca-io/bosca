package bosca.cdn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException

class CloudflareCdnManager(
    private val apiToken: String,
    private val zoneId: String,
    private val client: okhttp3.Call.Factory = OkHttpClient()
) : CdnManager {

    private val log = LoggerFactory.getLogger(CloudflareCdnManager::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun clearCache(): Boolean = withContext(Dispatchers.IO) {
        val url = "https://api.cloudflare.com/client/v4/zones/$zoneId/purge_cache"
        val body = PurgeRequest(purge_everything = true)
        val jsonBody = json.encodeToString(body)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log.error("Failed to clear Cloudflare cache: {} {}", response.code, response.body.string())
                    return@use false
                }
                val responseBody = response.body.string()
                val cloudflareResponse = json.decodeFromString<CloudflareResponse>(responseBody)
                if (!cloudflareResponse.success) {
                    log.error("Cloudflare returned success=false: {}", responseBody)
                    return@use false
                }
                true
            }
        } catch (e: IOException) {
            log.error("Exception clearing Cloudflare cache", e)
            false
        }
    }

    @Serializable
    private data class PurgeRequest(val purge_everything: Boolean)

    @Serializable
    private data class CloudflareResponse(val success: Boolean)
}
