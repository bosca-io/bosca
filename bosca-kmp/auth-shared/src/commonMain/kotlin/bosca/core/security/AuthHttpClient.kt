package bosca.core.security

import bosca.core.security.model.BoscaAuthConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hand-rolled GraphQL-over-HTTP transport for the auth library, used instead
 * of an Apollo client. It POSTs
 * `{ "query", "variables" }` to the GraphQL endpoint and unwraps the
 * `{ data, errors }` envelope. Bearer tokens are attached **per call** — never
 * via an auto-refreshing interceptor, which would recurse through token refresh.
 *
 * Kotlin port of the TypeScript `graphql.ts`, which likewise hand-rolls fetch.
 *
 * Error translation preserves the contract [TokenManager] depends on: transport
 * problems become [NetworkError] (a recoverable blip — the refresh token is kept),
 * while server-reported errors become typed [BoscaAuthError]s via [toAuthError].
 */
class AuthHttpClient(
    config: BoscaAuthConfig,
    private val client: HttpClient = defaultClient(),
) {
    private val endpoint: String = config.graphqlUrl ?: "${config.apiUrl.trimEnd('/')}/graphql"

    suspend fun <T> execute(
        query: String,
        variables: JsonObject,
        dataSerializer: KSerializer<T>,
        token: String? = null,
    ): T {
        // Serialize the body with the compiler-generated serializer up front rather
        // than handing a GraphQLRequest object to setBody(): that route makes
        // ContentNegotiation resolve the serializer reflectively (serializer(KType)),
        // which is unavailable in the CLI's GraalVM native image and fails there with
        // "Serializer for class 'GraphQLRequest' is not found". TextContent is an
        // OutgoingContent, so ContentNegotiation passes it through untouched. This
        // mirrors the explicit-serializer response path below.
        val requestBody = JSON.encodeToString(
            GraphQLRequest.serializer(),
            GraphQLRequest(query = query, variables = variables),
        )
        val response: HttpResponse = try {
            client.post(endpoint) {
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(TextContent(requestBody, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NetworkError(e.message ?: "Network request failed")
        }

        if (!response.status.isSuccess()) {
            if (response.status.value == 401 || response.status.value == 403) {
                throw AuthenticationRejectedError(response.status.value)
            }
            throw NetworkError("GraphQL request failed with HTTP ${response.status.value}")
        }

        val envelope = try {
            JSON.decodeFromString(GraphQLResponse.serializer(dataSerializer), response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GraphQLAuthError("Failed to parse GraphQL response: ${e.message}")
        }

        val errors = envelope.errors
        if (!errors.isNullOrEmpty()) {
            val messages = errors.map { it.message }
            val first = errors.first()
            // The server reports its machine-readable error code under `extensions.code`;
            // map it to a typed BoscaAuthError the UI can act on (e.g. routing an
            // unverified principal to the verification flow).
            val code = (first.extensions?.get("code") as? JsonPrimitive)?.contentOrNull
            throw toAuthError(code, first.message, messages)
        }

        return envelope.data ?: throw GraphQLAuthError("GraphQL response contained no data")
    }

    companion object {
        /**
         * Shared JSON: tolerant of unselected response fields and emits model
         * defaults so GraphQL inputs (e.g. `ProfileInput.slug`) are always present.
         */
        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private fun defaultClient(): HttpClient = HttpClient {
            install(ContentNegotiation) { json(JSON) }
        }
    }
}
