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
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GetCollectionBySlugCoverageTest {

    private val graphQLService = mockk<GraphQLService>(relaxed = true)
    private val tracer = mockk<Tracer>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val authenticationContext = mockk<AuthenticationContext>(relaxed = true)

    private val route = GetCollectionBySlug(graphQLService, tracer, json)

    private fun createCall(
        pathParams: Map<String, String> = mapOf("slug" to "genesis"),
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
        assertEquals("GetCollectionBySlug", route.getOperationName(call, authenticationContext))
    }

    @Test
    fun `response path targets content slug`() {
        assertEquals(listOf("content", "slug"), route.responsePath)
    }

    @Test
    fun `variables use slug from path parameter`() = runTest {
        val call = createCall(pathParams = mapOf("slug" to "matthew"))

        val vars = route.getVariables(call, authenticationContext)

        assertEquals(JsonPrimitive("matthew"), vars["slug"])
        assertEquals(1, vars.size)
    }

    @Test
    fun `variables error when slug path parameter missing`() = runTest {
        val call = createCall(pathParams = emptyMap())

        val exception = assertFailsWith<IllegalStateException> {
            route.getVariables(call, authenticationContext)
        }
        assertTrue(exception.message?.contains("missing slug") == true)
    }

    @Test
    fun `query references the operation and slug variable`() = runTest {
        val call = createCall()

        val query = route.getQuery(call, authenticationContext)

        assertTrue(query.contains("query GetCollectionBySlug(\$slug: String!)"))
        assertTrue(query.contains("slug(slug: \$slug)"))
        assertTrue(query.contains("... on Collection"))
        assertTrue(query.contains("metadataRelationships"))
        assertTrue(query.contains("languageVariant"))
    }
}
