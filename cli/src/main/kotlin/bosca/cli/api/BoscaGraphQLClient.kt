package bosca.cli.api

import bosca.graphql.client.GraphQLJson
import bosca.graphql.client.GraphQLResponse
import bosca.graphql.client.GraphQLUpload
import bosca.graphql.client.GraphQLUploadClient
import bosca.graphql.client.buildMultipartForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.cancellation.CancellationException

/**
 * The CLI's transport for the Bosca-native typed GraphQL client. Rides the CLI's existing OkHttp stack
 * ([NetworkClient.http]) and token plumbing rather than the module's default JDK transport, so it shares the
 * tuned connection pool/timeouts and stays consistent with the rest of the CLI. Implements
 * [GraphQLUploadClient] — file uploads ride the graphql-multipart-request-spec over OkHttp `MultipartBody`.
 *
 * Auth/retry: each request carries the current bearer token (auto-refreshing if *locally* expired), and on a
 * server `401` for a still-locally-valid token it force-refreshes the session once via [onUnauthorized] and
 * retries with the new token. If no refresh is possible (a static `--token`, or an already-revoked refresh
 * token) the original `401` is surfaced as a [BoscaHttpException]. The same retry covers uploads.
 * Subscriptions are not handled here — see [BoscaWebSocketClient].
 */
class BoscaGraphQLClient(
    private val url: String,
    private val http: OkHttpClient,
    private val token: suspend () -> String?,
    private val onUnauthorized: suspend () -> String?,
) : GraphQLUploadClient {

    override suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse {
        val payload = buildJsonObject {
            put("query", JsonPrimitive(document))
            if (operationName != null) put("operationName", JsonPrimitive(operationName))
            if (variables != null) put("variables", variables)
        }.toString()
        return send(payload.toRequestBody(JSON_MEDIA_TYPE))
    }

    override suspend fun executeUpload(
        document: String,
        variables: JsonObject?,
        operationName: String?,
        uploads: List<GraphQLUpload>,
    ): GraphQLResponse {
        val form = buildMultipartForm(document, variables, operationName, uploads)
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("operations", form.operations)
            .addFormDataPart("map", form.map)
        form.files.forEachIndexed { index, file ->
            multipart.addFormDataPart(
                index.toString(),
                file.filename,
                file.content.toRequestBody(file.contentType.toMediaType()),
            )
        }
        return send(multipart.build())
    }

    /** Send [body], applying the bearer token and the one-shot 401 force-refresh-and-retry, then parse the envelope. */
    private suspend fun send(body: RequestBody): GraphQLResponse {
        val current = token()
        val first = post(body, current)
        val response = if (first.code == 401) retryAfterRefresh(body, current, first) else first
        response.use {
            if (it.code / 100 != 2) {
                throw BoscaHttpException(
                    it.code,
                    "GraphQL request to $url failed with HTTP ${it.code}: ${it.body.string()}",
                )
            }
            val text = it.body.string()
            return GraphQLJson.decodeFromString(GraphQLResponse.serializer(), text)
        }
    }

    /** Force-refresh once and re-issue with the new token; keep the original `401` if no usable refresh exists. */
    private suspend fun retryAfterRefresh(body: RequestBody, current: String?, unauthorized: Response): Response {
        val refreshed = try {
            onUnauthorized()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Refresh failed (e.g. the refresh token is also revoked) — surface the original 401.
            null
        }
        if (refreshed == null || refreshed == current) return unauthorized
        unauthorized.close() // discard the 401 body before re-issuing
        return post(body, refreshed)
    }

    private suspend fun post(body: RequestBody, bearer: String?): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        http.newCall(builder.build()).execute()
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * A non-2xx HTTP response from the GraphQL transport. Carries the [statusCode] so commands can give the same
 * tailored 401/403 messaging the Apollo path did via `ApolloHttpException`.
 */
class BoscaHttpException(val statusCode: Int, message: String) : Exception(message)
