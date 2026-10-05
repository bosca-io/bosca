package bosca.graphql.client

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A multiplatform [GraphQLUploadClient] over Ktor — the request/response transport the generated typed
 * operations ride on Kotlin/Multiplatform (Android, iOS, JVM, JS, Wasm), where the JVM-only
 * [HttpGraphQLClient] doesn't reach. POSTs the standard `{ query, operationName?, variables? }` envelope to
 * [endpoint] and parses the `{ data, errors }` response; also performs file uploads via the
 * graphql-multipart-request-spec.
 *
 * [headers] are sent on every request (e.g. a fixed `Authorization`); [headerProvider] is consulted per
 * request for dynamic headers (e.g. a freshly-refreshed bearer token), and [boscaInfoProvider] supplies the
 * installation, application, and version identity. Dynamic headers override [headers], and Bosca identity
 * overrides both on key collision. The [httpClient] (and therefore the engine) is supplied by the caller, so
 * the consuming app owns engine choice + configuration; this module stays engine-agnostic. Subscriptions need
 * a separate streaming transport.
 */
class KtorGraphQLClient(
    private val endpoint: String,
    private val httpClient: HttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val headerProvider: suspend () -> Map<String, String> = { emptyMap() },
    private val instrumentation: GraphQLRequestInstrumentation = GraphQLRequestInstrumentation(),
    private val boscaInfoProvider: suspend () -> BoscaGraphQLClientInfo?,
) : GraphQLUploadClient {

    /** Creates a client without Bosca application identity headers. */
    constructor(
        endpoint: String,
        httpClient: HttpClient,
        headers: Map<String, String> = emptyMap(),
        headerProvider: suspend () -> Map<String, String> = { emptyMap() },
        instrumentation: GraphQLRequestInstrumentation = GraphQLRequestInstrumentation(),
    ) : this(endpoint, httpClient, headers, headerProvider, instrumentation, { null })

    override suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse =
        instrumentation.execute(operationName) {
        val payload = buildJsonObject {
            put("query", JsonPrimitive(document))
            if (operationName != null) put("operationName", JsonPrimitive(operationName))
            if (variables != null) put("variables", variables)
        }
        val dynamicHeaders = headerProvider()
        val boscaInfo = boscaInfoProvider()
        val response = httpClient.post(endpoint) {
            applyHeaders(dynamicHeaders, boscaInfo)
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        parse(response)
    }

    override suspend fun executeUpload(
        document: String,
        variables: JsonObject?,
        operationName: String?,
        uploads: List<GraphQLUpload>,
    ): GraphQLResponse = instrumentation.execute(operationName) {
        val form = buildMultipartForm(document, variables, operationName, uploads)
        val parts = formData {
            append("operations", form.operations)
            append("map", form.map)
            form.files.forEachIndexed { index, file ->
                append(
                    index.toString(),
                    file.content,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.filename}\"")
                        append(HttpHeaders.ContentType, file.contentType)
                    },
                )
            }
        }
        val dynamicHeaders = headerProvider()
        val boscaInfo = boscaInfoProvider()
        val response = httpClient.post(endpoint) {
            applyHeaders(dynamicHeaders, boscaInfo)
            setBody(MultiPartFormDataContent(parts))
        }
        parse(response)
    }

    /** Attach `Accept: application/json`, caller headers, and the standard Bosca client identity. */
    private fun HttpRequestBuilder.applyHeaders(
        dynamic: Map<String, String>,
        boscaInfo: BoscaGraphQLClientInfo?,
    ) {
        headers {
            append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            (this@KtorGraphQLClient.headers + dynamic)
                .forEach { (name, value) -> append(name, value) }
            boscaInfo?.headers()?.forEach { (name, value) -> set(name, value) }
        }
    }

    private suspend fun parse(response: HttpResponse): GraphQLResponse {
        check(response.status.isSuccess()) {
            "GraphQL request to $endpoint failed with HTTP ${response.status.value}: ${response.bodyAsText()}"
        }
        return GraphQLJson.decodeFromString(GraphQLResponse.serializer(), response.bodyAsText())
    }
}
