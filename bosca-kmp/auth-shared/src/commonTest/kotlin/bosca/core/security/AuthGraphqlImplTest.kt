package bosca.core.security

import bosca.core.security.model.BoscaAuthConfig
import bosca.core.security.type.ProfileType
import bosca.core.security.type.ProfileVisibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Locks the hand-rolled Ktor transport that replaced Apollo: that responses map
 * to domain models, that `extensions.code` becomes a typed [BoscaAuthError], and
 * — critically for [TokenManager]'s refresh resilience — that transport failures
 * surface as [NetworkError], not [GraphQLAuthError].
 */
class AuthGraphqlImplTest {

    private val config = BoscaAuthConfig(apiUrl = "https://api.test", graphqlUrl = "https://api.test/graphql")

    private fun graphqlReturning(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): AuthGraphqlImpl {
        val engine = MockEngine { request ->
            onRequest(request)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(AuthHttpClient.JSON) } }
        return AuthGraphqlImpl(AuthHttpClient(config, client))
    }

    @Test
    fun loginWithPassword_maps_response_and_posts_to_endpoint() = runTest {
        var seenUrl: String? = null
        var seenMethod: HttpMethod? = null
        val graphql = graphqlReturning(
            body = LOGIN_OK,
            onRequest = { seenUrl = it.url.toString(); seenMethod = it.method },
        )

        val response = graphql.loginWithPassword("a@b.com", "pw")

        assertEquals("https://api.test/graphql", seenUrl)
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("jwt-access", response.token.token)
        assertEquals(111, response.token.expiresAt)
        assertEquals("refresh-1", response.refreshToken)
        assertEquals(true, response.principal.verified)
        assertEquals(1, response.profile?.size)
        assertEquals(ProfileType.GENERIC, response.profile?.first()?.type)
        assertEquals(ProfileVisibility.USER, response.profile?.first()?.visibility)
    }

    @Test
    fun request_body_is_a_self_serialized_graphql_envelope() = runTest {
        // The body must be serialized by the transport itself (TextContent built
        // from GraphQLRequest.serializer()), never handed to ContentNegotiation as
        // a bare object — reflective serializer resolution is unavailable in the
        // CLI's GraalVM native image. Assert both that the body is TextContent and
        // that it carries a well-formed {query, variables} GraphQL envelope.
        var seenBody: String? = null
        val graphql = graphqlReturning(
            body = LOGIN_OK,
            onRequest = { seenBody = (it.body as? TextContent)?.text },
        )

        graphql.loginWithPassword("a@b.com", "pw")

        val body = seenBody ?: error("request body was not TextContent")
        val envelope = AuthHttpClient.JSON.parseToJsonElement(body).jsonObject
        assertTrue("query" in envelope, "request envelope must carry a GraphQL query")
        assertTrue("variables" in envelope, "request envelope must carry variables")
    }

    @Test
    fun authenticated_call_attaches_bearer_token() = runTest {
        var authHeader: String? = null
        val graphql = graphqlReturning(
            body = """{"data":{"security":{"login":{"signOut":true}}}}""",
            onRequest = { authHeader = it.headers[HttpHeaders.Authorization] },
        )

        graphql.signOut("the-token")

        assertEquals("Bearer the-token", authHeader)
    }

    @Test
    fun graphql_error_code_maps_to_typed_error() = runTest {
        val graphql = graphqlReturning(
            body = """{"errors":[{"message":"nope","extensions":{"code":"INVALID_CREDENTIALS"}}]}""",
        )
        assertFailsWith<InvalidCredentialsError> { graphql.loginWithPassword("a@b.com", "bad") }
    }

    @Test
    fun transport_failure_is_a_NetworkError_not_a_graphql_error() = runTest {
        val engine = MockEngine { throw RuntimeException("connection refused") }
        val client = HttpClient(engine) { install(ContentNegotiation) { json(AuthHttpClient.JSON) } }
        val graphql = AuthGraphqlImpl(AuthHttpClient(config, client))

        val error = assertFailsWith<NetworkError> { graphql.loginWithPassword("a@b.com", "pw") }
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun non_2xx_status_is_a_NetworkError() = runTest {
        val graphql = graphqlReturning(status = HttpStatusCode.BadGateway, body = "upstream down")
        assertFailsWith<NetworkError> { graphql.loginWithPassword("a@b.com", "pw") }
    }

    @Test
    fun http_401_is_an_authentication_rejection() = runTest {
        val graphql = graphqlReturning(status = HttpStatusCode.Unauthorized, body = "unauthorized")
        val error = assertFailsWith<AuthenticationRejectedError> {
            graphql.loginWithPassword("a@b.com", "pw")
        }
        assertEquals(401, error.statusCode)
    }

    @Test
    fun http_403_is_an_authentication_rejection() = runTest {
        val graphql = graphqlReturning(status = HttpStatusCode.Forbidden, body = "forbidden")
        val error = assertFailsWith<AuthenticationRejectedError> {
            graphql.loginWithPassword("a@b.com", "pw")
        }
        assertEquals(403, error.statusCode)
    }

    @Test
    fun exchangeToken_mapsResponseFromSecurityLoginExchangeTokenPath() = runTest {
        // The CLI's OAuth loopback flow exchanges the captured token via this op;
        // it unwraps a different selection path (security.login.exchangeToken)
        // than password login, so it gets its own coverage.
        val graphql = graphqlReturning(body = EXCHANGE_OK)

        val response = graphql.exchangeToken("oauth-exchange-token")

        assertEquals("jwt-access", response.token.token)
        assertEquals("refresh-1", response.refreshToken)
        assertEquals(true, response.principal.verified)
    }

    private companion object {
        val LOGIN_OK = loginResponseEnvelope("password")
        val EXCHANGE_OK = loginResponseEnvelope("exchangeToken")

        /** A `security.login.<field>` envelope carrying a complete LoginResponse. */
        private fun loginResponseEnvelope(field: String) = """
            {"data":{"security":{"login":{"$field":{
              "principal":{"id":"00000000-0000-0000-0000-000000000001","verified":true,"primaryProfileId":"00000000-0000-0000-0000-000000000002"},
              "profile":[{"id":"00000000-0000-0000-0000-000000000002","name":"Test","type":"GENERIC","visibility":"USER","slug":null,"isPrimary":true,"attributes":[]}],
              "token":{"token":"jwt-access","expiresAt":111,"issuedAt":100},
              "refreshToken":"refresh-1"
            }}}}}
        """.trimIndent()
    }
}
