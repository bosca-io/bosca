package bosca.graphql.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A default [GraphQLClient] over the JDK HTTP client — the request/response transport the generated typed
 * operations ride. POSTs the standard `{ query, operationName?, variables? }` envelope to [endpoint] and parses
 * the `{ data, errors }` response. [headers] are sent on every request (e.g. `Authorization`); [headerProvider]
 * is consulted per request for dynamic headers (e.g. a refreshed bearer token), and [boscaInfoProvider]
 * supplies the installation, application, and version identity. Dynamic headers override [headers], and Bosca
 * identity overrides both on key collision.
 *
 * Also implements [GraphQLUploadClient] — file uploads ride the graphql-multipart-request-spec. The send is
 * synchronous; call it from a background dispatcher if you need to avoid blocking. No streaming — subscriptions
 * need a separate transport ([WebSocketGraphQLClient]).
 */
class HttpGraphQLClient(
    private val endpoint: String,
    private val headers: Map<String, String> = emptyMap(),
    private val headerProvider: () -> Map<String, String> = { emptyMap() },
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
    private val boscaInfoProvider: () -> BoscaGraphQLClientInfo?,
) : GraphQLUploadClient {

    /** Creates a client without Bosca application identity headers. */
    constructor(
        endpoint: String,
        headers: Map<String, String> = emptyMap(),
        headerProvider: () -> Map<String, String> = { emptyMap() },
        httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
    ) : this(endpoint, headers, headerProvider, httpClient, { null })

    override suspend fun execute(document: String, variables: JsonObject?, operationName: String?): GraphQLResponse {
        val payload = buildJsonObject {
            put("query", JsonPrimitive(document))
            if (operationName != null) put("operationName", JsonPrimitive(operationName))
            if (variables != null) put("variables", variables)
        }
        val builder = baseRequest()
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        return send(builder)
    }

    override suspend fun executeUpload(
        document: String,
        variables: JsonObject?,
        operationName: String?,
        uploads: List<GraphQLUpload>,
    ): GraphQLResponse {
        val form = buildMultipartForm(document, variables, operationName, uploads)
        val builder = baseRequest()
            .header("Content-Type", "multipart/form-data; boundary=$BOUNDARY")
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(form)))
        return send(builder)
    }

    private fun baseRequest(): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(120))
            .header("Accept", "application/json")
        (headers + headerProvider())
            .forEach { (name, value) -> builder.header(name, value) }
        boscaInfoProvider()?.headers()?.forEach { (name, value) -> builder.setHeader(name, value) }
        return builder
    }

    private fun send(builder: HttpRequest.Builder): GraphQLResponse {
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() / 100 == 2) {
            "GraphQL request to $endpoint failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return GraphQLJson.decodeFromString(GraphQLResponse.serializer(), response.body())
    }

    /** Assemble the multipart/form-data body: the `operations` + `map` text parts, then one binary part per file. */
    private fun multipartBody(form: MultipartForm): ByteArray {
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        text("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"operations\"\r\n\r\n${form.operations}\r\n")
        text("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"map\"\r\n\r\n${form.map}\r\n")
        form.files.forEachIndexed { index, file ->
            text("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$index\"; filename=\"${file.filename}\"\r\n")
            text("Content-Type: ${file.contentType}\r\n\r\n")
            out.write(file.content)
            text("\r\n")
        }
        text("--$BOUNDARY--\r\n")
        return out.toByteArray()
    }

    private companion object {
        private const val BOUNDARY = "----BoscaGraphQLMultipartBoundary7MA4YWxkTrZu0gW"
    }
}
