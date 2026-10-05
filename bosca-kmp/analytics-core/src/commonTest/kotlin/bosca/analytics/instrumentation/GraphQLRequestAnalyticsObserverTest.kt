package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsEvent
import bosca.analytics.api.AnalyticsEventSink
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.testAnalytics
import bosca.graphql.client.GraphQLRequest
import bosca.graphql.client.GraphQLRequestOutcome
import bosca.graphql.client.GraphQLRequestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphQLRequestAnalyticsObserverTest {
    @Test
    fun `request outcomes become safe analytics events`() = runTest {
        val sink = RecordingSink()
        val analytics = testAnalytics(sink)
        val observer = GraphQLRequestAnalyticsObserver(analytics)

        observer.onComplete(result(null, GraphQLRequestOutcome.SUCCESS, durationMillis = 4))
        observer.onComplete(result("Cancelled", GraphQLRequestOutcome.CANCELLED, durationMillis = 5))
        observer.onComplete(result("GraphqlFailure", GraphQLRequestOutcome.GRAPHQL_ERROR, durationMillis = 6))
        observer.onComplete(
            result(
                operationName = "TransportFailure",
                outcome = GraphQLRequestOutcome.TRANSPORT_ERROR,
                durationMillis = 7,
                errorType = "IllegalStateException",
            ),
        )

        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) {
                sink.events.first { it.size == 4 }
            }
        }

        val events = sink.events.value
        val eventsById = events.associateBy { it.element.id }
        assertEquals(
            setOf(
                AnalyticsEventType.COMPLETION,
                AnalyticsEventType.INTERACTION,
                AnalyticsEventType.ERROR,
            ),
            events.map { it.type }.toSet(),
        )
        assertEquals("success", eventsById.getValue("anonymous").element.extras["outcome"])
        assertEquals("4", eventsById.getValue("anonymous").element.extras["duration_ms"])
        assertNull(eventsById.getValue("anonymous").error)
        assertNull(eventsById.getValue("Cancelled").error)
        assertEquals("GRAPHQL_ERROR", eventsById.getValue("GraphqlFailure").error?.type)
        assertEquals("IllegalStateException", eventsById.getValue("TransportFailure").error?.type)
        assertEquals(setOf("graphql"), events.map { it.element.extras["instrumentation"] }.toSet())

        analytics.close()
    }

    private fun result(
        operationName: String?,
        outcome: GraphQLRequestOutcome,
        durationMillis: Long,
        errorType: String? = null,
    ) = GraphQLRequestResult(
        request = GraphQLRequest(operationName),
        outcome = outcome,
        durationMillis = durationMillis,
        errorType = errorType,
    )

    private class RecordingSink : AnalyticsEventSink() {
        val events = MutableStateFlow<List<AnalyticsEvent>>(emptyList())

        override suspend fun onAdd(original: AnalyticsEvent, event: AnalyticsEvent) {
            events.update { it + event }
        }
    }
}
