package bosca.content.collection.routes

import bosca.graphql.GraphQLService
import bosca.security.service.AuthenticationContext
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetCollectionCoverageTest {

    private val graphQLService = mockk<GraphQLService>(relaxed = true)
    private val tracer = mockk<Tracer>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val authenticationContext = mockk<AuthenticationContext>(relaxed = true)

    private val route = GetCollection(graphQLService, tracer, json)

    private fun createCall(
        pathParams: Map<String, String> = mapOf("id" to "col-123"),
        queryParams: Map<String, String> = emptyMap(),
    ): ServerCall {
        val request = mockk<ServerRequest>(relaxed = true)
        every { request.queryParameters } returns Parameters(queryParams.mapValues { listOf(it.value) })
        val call = mockk<ServerCall>(relaxed = true)
        every { call.request } returns request
        every { call.pathParameters } returns Parameters(pathParams.mapValues { listOf(it.value) })
        return call
    }

    @AfterTest
    fun tearDown() {
        io.mockk.clearAllMocks()
    }

    @Test
    fun `operation name is constant`() = runTest {
        val call = createCall()
        assertEquals("GetCollection", route.getOperationName(call, authenticationContext))
    }

    @Test
    fun `response path targets content collections collection`() {
        assertEquals(listOf("content", "collections", "collection"), route.responsePath)
    }

    @Test
    fun `variables encode id from path parameter`() = runTest {
        val call = createCall(pathParams = mapOf("id" to "col-123"))

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonPrimitive("col-123"), vars["id"])
    }

    @Test
    fun `variables encode id as JsonNull when id path parameter missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonNull, vars["id"])
    }

    @Test
    fun `query references GetCollection operation and collection selector`() = runTest {
        val call = createCall()

        val query = route.getQuery(call, authenticationContext)

        assertTrue(query.contains("query GetCollection(\$id: String!)"))
        assertTrue(query.contains("collection(id: \$id)"))
        assertTrue(query.contains("metadataRelationships"))
        assertTrue(query.contains("languageVariant"))
    }
}
