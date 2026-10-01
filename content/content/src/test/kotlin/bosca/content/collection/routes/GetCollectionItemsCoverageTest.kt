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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetCollectionItemsCoverageTest {

    private val graphQLService = mockk<GraphQLService>(relaxed = true)
    private val tracer = mockk<Tracer>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val authenticationContext = mockk<AuthenticationContext>(relaxed = true)

    private val route = GetCollectionItems(graphQLService, tracer, json)

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
        assertEquals("GetCollectionItems", route.getOperationName(call, authenticationContext))
    }

    @Test
    fun `response path targets content collections collection`() {
        assertEquals(listOf("content", "collections", "collection"), route.responsePath)
    }

    @Test
    fun `variables use id and default offset and limit when query params absent`() = runTest {
        val call = createCall(queryParams = emptyMap())

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonPrimitive("col-123"), vars["id"])
        assertEquals(JsonPrimitive(0), vars["offset"])
        assertEquals(JsonPrimitive(10), vars["limit"])
        assertFalse(vars.containsKey("languageTag"))
    }

    @Test
    fun `variables parse valid offset and limit`() = runTest {
        val call = createCall(queryParams = mapOf("offset" to "25", "limit" to "5"))

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonPrimitive(25), vars["offset"])
        assertEquals(JsonPrimitive(5), vars["limit"])
    }

    @Test
    fun `variables fall back to defaults when offset and limit are non-numeric`() = runTest {
        val call = createCall(queryParams = mapOf("offset" to "abc", "limit" to "xyz"))

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonPrimitive(0), vars["offset"])
        assertEquals(JsonPrimitive(10), vars["limit"])
    }

    @Test
    fun `variables include languageTag when language param present`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "es"))

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonPrimitive("es"), vars["languageTag"])
    }

    @Test
    fun `variables omit languageTag when language param absent`() = runTest {
        val call = createCall(queryParams = mapOf("offset" to "3"))

        val vars = route.getVariables(call, authenticationContext)

        assertFalse(vars.containsKey("languageTag"))
    }

    @Test
    fun `variables encode id as JsonNull when id path parameter missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonNull, vars["id"])
    }

    @Test
    fun `query omits languageTag clauses when language param absent`() = runTest {
        val call = createCall(queryParams = emptyMap())

        val query = route.getQuery(call, authenticationContext)

        assertFalse(query.contains("\$languageTag"))
        assertFalse(query.contains("languageTag: \$languageTag"))
        assertTrue(query.contains("query GetCollectionItems"))
        assertTrue(query.contains("collection(id: \$id)"))
        assertTrue(query.contains("items(offset: \$offset, limit: \$limit)"))
    }

    @Test
    fun `query includes languageTag clauses when language param present`() = runTest {
        val call = createCall(queryParams = mapOf("language" to "fr"))

        val query = route.getQuery(call, authenticationContext)

        assertTrue(query.contains("\$languageTag: String"))
        assertTrue(query.contains("languageTag: \$languageTag"))
    }
}
