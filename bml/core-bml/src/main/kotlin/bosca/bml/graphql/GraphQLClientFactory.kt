package bosca.bml.graphql

import bosca.server.http.await
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * An upstream GraphQL response passed through untouched: HTTP status, raw JSON body, and the
 * response headers that affect the browser session. Only `Set-Cookie` is relayed; generic upstream
 * headers can contain hop-by-hop or transport-specific values that do not belong on the BFF response.
 */
data class GraphQLForwardResponse(
    val status: Int,
    val body: String,
    val headers: List<Pair<String, String>> = emptyList(),
)

/** An upstream response relayed as-is: status, selected safe response headers, and the raw body. */
class ForwardedResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val contentType: String?,
    val body: ByteArray,
)

/**
 * The default [OkHttpClient] for GraphQL traffic. Every call this process makes goes to ONE host
 * (the configured GraphQL endpoint), and OkHttp's stock dispatcher allows only 5 concurrent
 * requests per host — which serializes concurrent page renders behind each other's data fetches.
 * Raise the per-host ceiling to the dispatcher's overall ceiling so concurrency is bounded once,
 * by total requests, not per host.
 */
internal fun defaultGraphQLHttpClient(): OkHttpClient =
    defaultGraphQLHttpClient(System.getenv("BML_APP_ID"), System.getenv("APP_VERSION"))

/** Attach the site's configured identity to both SSR operations and proxied GraphQL requests. */
internal fun defaultGraphQLHttpClient(appId: String?, appVersion: String?): OkHttpClient {
    val dispatcher = Dispatcher()
    dispatcher.maxRequests = 1024
    dispatcher.maxRequestsPerHost = dispatcher.maxRequests
    return OkHttpClient.Builder().dispatcher(dispatcher).addInterceptor { chain ->
        val request = chain.request().newBuilder().apply {
            appId?.takeIf(String::isNotBlank)?.let { header("X-App-ID", it) }
            appVersion?.takeIf(String::isNotBlank)?.let { header("X-App-Version", it) }
        }.build()
        chain.proceed(request)
    }.build()
}

/**
 * Mints per-request [GraphQLClient]s bound to the caller's token (passthrough) while
 * sharing one [OkHttpClient] — so the connection pool is reused across requests instead of rebuilt
 * per page render. `bml-server` holds one factory (for the configurable endpoint) and calls
 * [forToken] in each route handler.
 */
class GraphQLClientFactory(
    private val endpoint: String,
    private val httpClient: OkHttpClient = defaultGraphQLHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    /** A client for [endpoint] that forwards [token] (the incoming request's bearer) on every call. */
    fun forToken(token: String?): GraphQLClient =
        HttpGraphQLClient(endpoint, httpClient, json = json, defaultToken = token)

    /** A client that forwards both the request bearer and its stable installation identity. */
    fun forToken(token: String?, installationId: String?): GraphQLClient =
        HttpGraphQLClient(
            endpoint,
            httpClient,
            json = json,
            defaultToken = token,
            defaultInstallationId = installationId,
        )

    /** A per-request client forwarding bearer, installation, and optional analytics session identity. */
    fun forToken(token: String?, installationId: String?, analyticsSessionId: String?): GraphQLClient =
        HttpGraphQLClient(
            endpoint,
            httpClient,
            json = json,
            defaultToken = token,
            defaultInstallationId = installationId,
            defaultAnalyticsSessionId = analyticsSessionId,
        )

    /** The API base for sibling routes like `/oauth2` — the endpoint minus its `/graphql` path. */
    private val origin: String = endpoint.substringBefore("/graphql").trimEnd('/')

    /** Shares this factory's pool but never follows redirects — upstream 302s are relayed, not chased. */
    private val redirectlessClient: OkHttpClient by lazy {
        httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /**
     * Forwards a GET for [pathAndQuery] under the API origin untouched and relays the response —
     * status, `Location`/`Set-Cookie`, body. This backs `bml-server`'s same-origin `/oauth2`
     * pass-through (the auth SDK's `signInWithRedirect` navigates on the page's own origin; the
     * data plane answers with the provider redirect), mirroring Studio's oauth2 dev proxy.
     */
    suspend fun forwardGet(pathAndQuery: String, token: String? = null): ForwardedResponse {
        val request = Request.Builder()
            .url(origin + pathAndQuery)
            .get()
            .apply {
                token?.let { header("Authorization", if (it.startsWith("Bearer ")) it else "Bearer $it") }
            }
            .build()
        redirectlessClient.newCall(request).await().use { response ->
            val relayed = buildList {
                response.headers("Location").forEach { add("Location" to it) }
                response.headers("Set-Cookie").forEach { add("Set-Cookie" to it) }
            }
            return ForwardedResponse(response.code, relayed, response.header("Content-Type"), response.body.bytes())
        }
    }

    /**
     * Forwards a raw `{query, variables, operationName}` request [body] to [endpoint] untouched,
     * attaching the caller's [token] as a bearer, and returns the upstream status, session cookies,
     * and JSON body as-is (GraphQL errors stay in the payload — the browser client interprets them).
     * Relaying every `Set-Cookie` header is required for authentication mutations such as sign-out,
     * whose server-side session cleanup cannot be reproduced by browser JavaScript. This backs
     * `bml-server`'s same-origin `/graphql` BFF proxy, so browser code never needs CORS to reach the
     * data plane.
     */
    suspend fun forward(
        body: String,
        token: String?,
        requestOrigin: String? = null,
    ): GraphQLForwardResponse = forward(body, token, requestOrigin, null)

    /** Forwards a GraphQL request with its stable installation identity. */
    suspend fun forward(
        body: String,
        token: String?,
        requestOrigin: String?,
        installationId: String?,
    ): GraphQLForwardResponse = forward(body, token, requestOrigin, installationId, null)

    /** Forwards a GraphQL request with its installation and optional analytics session identity. */
    suspend fun forward(
        body: String,
        token: String?,
        requestOrigin: String?,
        installationId: String?,
        analyticsSessionId: String?,
    ): GraphQLForwardResponse {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply {
                token?.let {
                    header("Authorization", if (it.startsWith("Bearer ")) it else "Bearer $it")
                }
                requestOrigin?.let { header("Origin", it) }
                installationId?.takeIf(String::isNotBlank)?.let {
                    header(INSTALLATION_ID_HEADER, it)
                }
                analyticsSessionId?.takeIf(String::isNotBlank)?.let {
                    header("X-BA-Session-ID", it)
                }
            }
            .build()
        httpClient.newCall(request).await().use { response ->
            val relayed = response.headers("Set-Cookie").map { "Set-Cookie" to it }
            return GraphQLForwardResponse(response.code, response.body.string(), relayed)
        }
    }

    private companion object {
        private const val INSTALLATION_ID_HEADER = "X-Installation-ID"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
