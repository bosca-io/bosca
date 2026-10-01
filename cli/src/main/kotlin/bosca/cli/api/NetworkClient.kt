package bosca.cli.api

import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.GraphQLUploadClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit

class NetworkClient(val url: String, val wsUrl: String? = null) {

    /**
     * The bearer token for outgoing GraphQL requests, resolved per request by
     * [CliAuth]. It is a single suspend provider so the one mechanism covers
     * every case: a static credential returns the same token each call (an API
     * token, or `--token`), while a refreshable session returns
     * `BoscaAuth.getToken()`, which lazily refreshes an expired access token —
     * keeping even long-running commands authenticated. Null until authenticated.
     */
    var tokenProvider: (suspend () -> String?)? = null

    /**
     * Optional hook to force-refresh the session after a server `401` on a
     * still-locally-valid token, returning a fresh token for one retry (or null
     * to give up). Wired by [CliAuth] to `BoscaAuth.refresh()` for managed
     * sessions; left null for a static `--token`, which cannot be refreshed.
     */
    var onUnauthorized: (suspend () -> String?)? = null

    private suspend fun resolveToken(): String? = tokenProvider?.invoke()

    val http = OkHttpClient.Builder()
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
                maxIdleConnections = 50,
                keepAliveDuration = 1,
                timeUnit = TimeUnit.MINUTES
            )
        )
        .build()

    /**
     * Transport for the Bosca-native typed GraphQL client. Rides the [http] stack and the token/401 plumbing,
     * so a generated operation runs with `boscaGraphql.execute(Op, vars)`; file uploads ride the
     * graphql-multipart-request-spec via [GraphQLUploadClient.executeUpload].
     */
    val boscaGraphql: GraphQLUploadClient by lazy {
        BoscaGraphQLClient(
            url = url,
            http = http,
            token = { resolveToken() },
            onUnauthorized = { onUnauthorized?.invoke() },
        )
    }

    /**
     * Subscription transport for the Bosca-native client. Speaks `graphql-transport-ws` over [http];
     * `boscaWs.subscribe(Op, vars)` returns a `Flow` of typed data.
     */
    val boscaWs: GraphQLSubscriptionClient by lazy {
        BoscaWebSocketClient(
            wsUrl = wsUrl ?: "ws://127.0.0.1:8000/ws",
            http = http,
            token = { resolveToken() },
        )
    }
}
