package bosca.bml.message.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

/**
 * Typed client for the BML Message Server's private render API —
 * the `KubernetesControllerClient` pattern: callers (the send path) construct this against the
 * server's cluster-internal URL and never speak HTTP themselves. Failures map to
 * [BmlMessageRenderException] with the server's reason, so delivery tracking records something
 * actionable.
 */
class BmlMessageServerClient(
    baseUrl: String,
    private val http: OkHttpClient = defaultClient(),
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Render [templateKey] of [project], forwarding [bearerToken] so the hosted template can use
     * the same authenticated Bosca GraphQL data plane as its caller.
     */
    suspend fun render(
        project: String,
        templateKey: String,
        request: RenderRequest,
        bearerToken: String?,
    ): RenderResponse =
        withContext(Dispatchers.IO) {
            val call = Request.Builder()
                .url("$base/render/$project/$templateKey")
                .post(json.encodeToString(RenderRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
                .apply {
                    bearerToken?.let {
                        header("Authorization", if (it.startsWith("Bearer ")) it else "Bearer $it")
                    }
                }
                .build()
            http.newCall(call).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    val reason = runCatching { json.decodeFromString(RenderError.serializer(), body).error }
                        .getOrDefault(body.take(200))
                    throw BmlMessageRenderException(response.code, "$project/$templateKey: $reason")
                }
                json.decodeFromString(RenderResponse.serializer(), body)
            }
        }

    /**
     * Fetch one bundled asset of a PUBLISHED version — the bytes behind a [RenderImage]
     * reference. Immutable forever (published versions never change), so callers cache by
     * (project, version, source) without invalidation.
     */
    suspend fun asset(project: String, version: String, source: String): ByteArray =
        withContext(Dispatchers.IO) {
            val call = Request.Builder()
                .url("$base/assets/$project/$version/$source")
                .get()
                .build()
            http.newCall(call).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BmlMessageRenderException(response.code, "asset $project@$version/$source: HTTP ${response.code}")
                }
                response.body.bytes()
            }
        }

    /** The projects the server currently hosts, with active version + template keys. */
    suspend fun hostedProjects(): List<HostedMessageProject> =
        withContext(Dispatchers.IO) {
            val call = Request.Builder().url("$base/projects").get().build()
            http.newCall(call).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BmlMessageRenderException(response.code, "hosted projects: HTTP ${response.code}")
                }
                json.decodeFromString(ListSerializer(HostedMessageProject.serializer()), response.body.string())
            }
        }

    /** The PUBLISHED versions of a hosted project, newest first — the pin/override choices. */
    suspend fun versions(project: String): List<String> =
        withContext(Dispatchers.IO) {
            val call = Request.Builder().url("$base/projects/$project/versions").get().build()
            http.newCall(call).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BmlMessageRenderException(response.code, "versions of '$project': HTTP ${response.code}")
                }
                json.decodeFromString(ListSerializer(String.serializer()), response.body.string())
            }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Renders are bounded server-side (BML_MESSAGE_RENDER_TIMEOUT_MS); the read timeout just needs
        // to outlast it so the server's typed error arrives instead of a client-side cutoff.
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .build()
    }
}

/**
 * A render the server refused or failed: [status] is the HTTP status (404 unknown
 * project/template, 503 no active version, 500 template failure/timeout), [message] carries the
 * server's reason — record it as the delivery-event failure reason.
 */
class BmlMessageRenderException(val status: Int, message: String) : RuntimeException(message)
