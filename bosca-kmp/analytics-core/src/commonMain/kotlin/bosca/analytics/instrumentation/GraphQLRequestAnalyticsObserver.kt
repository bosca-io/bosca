package bosca.analytics.instrumentation

import bosca.analytics.api.AnalyticsElement
import bosca.analytics.api.AnalyticsEventInput
import bosca.analytics.api.AnalyticsEventType
import bosca.analytics.api.ErrorInfo
import bosca.analytics.api.AnalyticsService
import bosca.graphql.client.GraphQLRequestObserver
import bosca.graphql.client.GraphQLRequestOutcome
import bosca.graphql.client.GraphQLRequestResult

/** Converts safe GraphQL request telemetry into automatic analytics events. */
internal class GraphQLRequestAnalyticsObserver(
    private val analytics: AnalyticsService,
) : GraphQLRequestObserver {
    override fun onComplete(result: GraphQLRequestResult) {
        val failed = result.outcome == GraphQLRequestOutcome.GRAPHQL_ERROR ||
            result.outcome == GraphQLRequestOutcome.TRANSPORT_ERROR
        analytics.recordAutomaticEvent(
            AnalyticsEventInput(
                type = when {
                    failed -> AnalyticsEventType.ERROR
                    result.outcome == GraphQLRequestOutcome.CANCELLED -> AnalyticsEventType.INTERACTION
                    else -> AnalyticsEventType.COMPLETION
                },
                element = AnalyticsElement(
                    id = result.request.operationName ?: "anonymous",
                    type = "graphql_request",
                    extras = mapOf(
                        "duration_ms" to result.durationMillis.toString(),
                        "instrumentation" to "graphql",
                        "outcome" to result.outcome.name.lowercase(),
                    ),
                ),
                error = if (failed) {
                    ErrorInfo(
                        message = "GraphQL request failed",
                        type = result.errorType ?: result.outcome.name,
                        fatal = false,
                    )
                } else {
                    null
                },
            ),
        )
    }
}
