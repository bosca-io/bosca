package bosca.graphql.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQLRequestInstrumentationTest {
    @Test
    fun `observers receive every terminal outcome without changing request behavior`() = runTest {
        val instrumentation = GraphQLRequestInstrumentation()
        val results = mutableListOf<GraphQLRequestResult>()
        val failingObserver = GraphQLRequestObserver { error("observer failed") }
        val observer = GraphQLRequestObserver(results::add)
        instrumentation.addObserver(failingObserver)
        instrumentation.addObserver(observer)

        val success = GraphQLResponse(data = JsonObject(emptyMap()))
        assertEquals(success, instrumentation.execute("Success") { success })
        instrumentation.execute(null) { GraphQLResponse(data = JsonObject(emptyMap()), errors = emptyList()) }
        instrumentation.execute("GraphQL") { GraphQLResponse(errors = listOf(GraphQLError("failed"))) }
        instrumentation.execute("PartialGraphQL") {
            GraphQLResponse(
                data = JsonObject(emptyMap()),
                errors = listOf(GraphQLError("partial failure")),
            )
        }
        instrumentation.execute("MissingData") { GraphQLResponse() }
        assertFailsWith<IllegalStateException> {
            instrumentation.execute("Transport") { error("transport failed") }
        }
        assertFailsWith<CancellationException> {
            instrumentation.execute("Cancelled") { throw CancellationException("cancelled") }
        }

        assertEquals(
            listOf(
                GraphQLRequestOutcome.SUCCESS,
                GraphQLRequestOutcome.SUCCESS,
                GraphQLRequestOutcome.GRAPHQL_ERROR,
                GraphQLRequestOutcome.GRAPHQL_ERROR,
                GraphQLRequestOutcome.GRAPHQL_ERROR,
                GraphQLRequestOutcome.TRANSPORT_ERROR,
                GraphQLRequestOutcome.CANCELLED,
            ),
            results.map { it.outcome },
        )
        assertNull(results.first().errorType)
        assertEquals("IllegalStateException", results[5].errorType)
        assertEquals("CancellationException", results[6].errorType)
        assertTrue(results.all { it.durationMillis >= 0 })
        assertNull(results[1].request.operationName)

        instrumentation.removeObserver(observer)
        instrumentation.removeObserver(failingObserver)
        instrumentation.execute("Unobserved") { success }
        assertEquals(7, results.size)
    }
}
