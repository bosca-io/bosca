package bosca.routes

import bosca.graphql.GraphQLService
import bosca.security.service.AuthenticationContext
import bosca.server.ServerCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

private open class TestGraphQLProxyRoute(
    service: GraphQLService,
    tracer: Tracer,
    json: Json,
    override val responsePath: List<String>,
    private val variables: JsonObject? = null,
) : GraphQLProxyRoute(service, tracer, json) {
    override suspend fun getOperationName(call: ServerCall, authenticationContext: AuthenticationContext) = "Operation"

    override suspend fun getQuery(call: ServerCall, authenticationContext: AuthenticationContext) = "query Operation { value }"

    override suspend fun getVariables(call: ServerCall, authenticationContext: AuthenticationContext) = variables

    suspend fun run(call: ServerCall, authenticationContext: AuthenticationContext) = execute(call, authenticationContext)
}

class GraphQLProxyRouteTest {

    private val service = mockk<GraphQLService>()
    private val tracer = mockk<Tracer>(relaxed = true)
    private val call = mockk<ServerCall>(relaxed = true)
    private val authentication = AuthenticationContext(null, null)

    @Test
    fun `transform follows response path and handles missing values`() = runTest {
        val route = TestGraphQLProxyRoute(service, tracer, Json, listOf("viewer", "name"))
        val response = buildJsonObject {
            put("viewer", buildJsonObject { put("name", "Ada") })
        }
        assertEquals(JsonPrimitive("Ada"), route.transform(response))
        assertEquals(null, route.transform(buildJsonObject { put("viewer", JsonObject(emptyMap())) }))
        assertEquals(response, TestGraphQLProxyRoute(service, tracer, Json, emptyList()).transform(response))
    }

    @Test
    fun `execute returns transformed data and forwards operation inputs`() = runTest {
        val variables = buildJsonObject { put("id", "1") }
        val response = buildJsonObject {
            put("data", buildJsonObject {
                put("viewer", buildJsonObject { put("name", "Ada") })
            })
        }
        coEvery { service.getAsJsonElement(call, "Operation", any(), variables, null) } returns response
        val route = TestGraphQLProxyRoute(service, tracer, Json, listOf("viewer", "name"), variables)

        val content = route.run(call, authentication)

        assertEquals(JsonPrimitive("Ada"), content.asJsonElement())
        coVerify(exactly = 1) {
            service.getAsJsonElement(call, "Operation", "query Operation { value }", variables, null)
        }
    }

    @Test
    fun `execute preserves graphql errors and substitutes null data`() = runTest {
        val errors = buildJsonObject { put("errors", JsonPrimitive("failure")) }
        coEvery { service.getAsJsonElement(any(), any(), any(), any(), any()) } returns errors
        var route = TestGraphQLProxyRoute(service, tracer, Json, listOf("value"))
        assertEquals(errors, route.run(call, authentication).asJsonElement())

        val missingData = buildJsonObject { put("extensions", JsonPrimitive("x")) }
        coEvery { service.getAsJsonElement(any(), any(), any(), any(), any()) } returns missingData
        route = object : TestGraphQLProxyRoute(service, tracer, Json, listOf("value")) {
            override suspend fun transform(element: JsonElement): JsonElement? = null
        }
        assertEquals(JsonNull, route.run(call, authentication).asJsonElement())
    }

    @Test
    fun `execute falls back to raw response when transformation throws`() = runTest {
        val response = buildJsonObject { put("data", JsonPrimitive("not-an-object")) }
        coEvery { service.getAsJsonElement(any(), any(), any(), any(), any()) } returns response
        val route = TestGraphQLProxyRoute(service, tracer, Json, listOf("value"))

        assertEquals(response, route.run(call, authentication).asJsonElement())
    }
}
