package bosca.graphql.codegen.cli

import bosca.graphql.client.GraphQLJson
import bosca.graphql.codegen.Introspection
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Posts an introspection [body] to [endpoint] with [headers] and returns the raw JSON response. */
fun interface IntrospectionTransport {
    fun post(endpoint: String, body: String, headers: Map<String, String>): String
}

/**
 * Refreshes a checked-in `schema.graphqls` from a live endpoint: POST the standard introspection query,
 * convert the result to SDL via [Introspection]. The HTTP call is isolated behind [IntrospectionTransport]
 * so request assembly + SDL conversion are unit-testable without a network.
 */
object SchemaDownloader {

    /** The JSON request body wrapping a GraphQL [query] (escaped via [JsonPrimitive]). */
    fun requestBody(query: String): String = """{"query":${JsonPrimitive(query)}}"""

    /** Fetch the schema from [endpoint] and return SDL. [headers] are extra request headers (e.g. `Authorization`). */
    fun download(
        endpoint: String,
        headers: Map<String, String> = emptyMap(),
        transport: IntrospectionTransport = HttpIntrospectionTransport,
    ): String {
        val raw = transport.post(endpoint, requestBody(Introspection.QUERY), headers)
        return Introspection.toSdl(GraphQLJson.parseToJsonElement(raw))
    }
}

/** Default [IntrospectionTransport] over the JDK HTTP client. */
object HttpIntrospectionTransport : IntrospectionTransport {
    override fun post(endpoint: String, body: String, headers: Map<String, String>): String {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        // `/ 100 == 2` (any 2xx) rather than `in 200..299`: the range lowers to `200 <= s && s <= 299`, whose
        // lower-bound arm is unreachable (HttpClient never surfaces a sub-200 status), leaving a dead branch.
        check(response.statusCode() / 100 == 2) {
            "Introspection request to $endpoint failed with HTTP ${response.statusCode()}: ${response.body()}"
        }
        return response.body()
    }
}
