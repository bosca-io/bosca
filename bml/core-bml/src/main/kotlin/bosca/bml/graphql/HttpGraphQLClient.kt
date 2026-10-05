package bosca.bml.graphql

import bosca.server.http.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A [GraphQLClient] over **OkHttp** — Bosca's standard HTTP client. Suspends on the response via
 * `bosca.server.http.await` (OkHttp's async `enqueue`), so it never ties up a thread. Posts
 * `{query, variables}` to [endpoint], forwards the caller's bearer [token] (per call, or
 * [defaultToken]), and returns the `data` element — throwing [GraphQLException] on a non-2xx
 * response or a populated `errors` array.
 */
class HttpGraphQLClient(
    private val endpoint: String,
    private val httpClient: OkHttpClient = defaultGraphQLHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val defaultToken: String? = null,
) : GraphQLClient {

    private var defaultInstallationId: String? = null
    private var defaultAnalyticsSessionId: String? = null

    /**
     * Creates a client that also forwards a stable installation identity.
     * The original four-argument constructor remains intact for binary
     * compatibility with already-compiled BML applications.
     */
    constructor(
        endpoint: String,
        httpClient: OkHttpClient,
        json: Json,
        defaultToken: String?,
        defaultInstallationId: String?,
    ) : this(endpoint, httpClient, json, defaultToken) {
        this.defaultInstallationId = defaultInstallationId
    }

    /** Creates a per-request client with installation and optional analytics session identity. */
    constructor(
        endpoint: String,
        httpClient: OkHttpClient,
        json: Json,
        defaultToken: String?,
        defaultInstallationId: String?,
        defaultAnalyticsSessionId: String?,
    ) : this(endpoint, httpClient, json, defaultToken, defaultInstallationId) {
        this.defaultAnalyticsSessionId = defaultAnalyticsSessionId
    }

    override suspend fun execute(
        query: String,
        variables: JsonObject?,
        operationName: String?,
        token: String?,
    ): JsonElement {
        val body = json.encodeToString(
            GraphQLRequest.serializer(),
            GraphQLRequest(query, variables, operationName),
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                (token ?: defaultToken)?.let {
                    header("Authorization", if (it.startsWith("Bearer ")) it else "Bearer $it")
                }
                defaultInstallationId?.takeIf(String::isNotBlank)?.let {
                    header(INSTALLATION_ID_HEADER, it)
                }
                defaultAnalyticsSessionId?.takeIf(String::isNotBlank)?.let {
                    header("X-BA-Session-ID", it)
                }
            }
            .build()

        httpClient.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw GraphQLException("GraphQL HTTP ${response.code}: $text")
            }
            val parsed = json.decodeFromString(GraphQLResponse.serializer(), text)
            if (!parsed.errors.isNullOrEmpty()) {
                throw GraphQLException(parsed.errors.joinToString("; ") { it.message })
            }
            return parsed.data ?: JsonNull
        }
    }

    private companion object {
        private const val INSTALLATION_ID_HEADER = "X-Installation-ID"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
