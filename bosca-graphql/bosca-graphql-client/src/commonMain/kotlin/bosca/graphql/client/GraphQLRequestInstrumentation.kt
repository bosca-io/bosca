package bosca.graphql.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/** Thread-safe observer registry shared by GraphQL clients and application instrumentation. */
class GraphQLRequestInstrumentation {
    private val observers = MutableStateFlow<List<GraphQLRequestObserver>>(emptyList())

    /** Adds [observer] to subsequent requests. */
    fun addObserver(observer: GraphQLRequestObserver) {
        observers.update { current -> current + observer }
    }

    /** Removes the exact [observer] instance. */
    fun removeObserver(observer: GraphQLRequestObserver) {
        observers.update { current -> current.filterNot { it === observer } }
    }

    internal suspend fun execute(
        operationName: String?,
        request: suspend () -> GraphQLResponse,
    ): GraphQLResponse {
        val started = TimeSource.Monotonic.markNow()
        return try {
            request().also { response ->
                complete(
                    operationName = operationName,
                    outcome = if (response.data == null || response.errors?.isNotEmpty() == true) {
                        GraphQLRequestOutcome.GRAPHQL_ERROR
                    } else {
                        GraphQLRequestOutcome.SUCCESS
                    },
                    durationMillis = started.elapsedNow().inWholeMilliseconds,
                )
            }
        } catch (error: CancellationException) {
            complete(operationName, GraphQLRequestOutcome.CANCELLED, started.elapsedNow().inWholeMilliseconds, error)
            throw error
        } catch (error: Throwable) {
            complete(operationName, GraphQLRequestOutcome.TRANSPORT_ERROR, started.elapsedNow().inWholeMilliseconds, error)
            throw error
        }
    }

    private fun complete(
        operationName: String?,
        outcome: GraphQLRequestOutcome,
        durationMillis: Long,
        error: Throwable? = null,
    ) {
        val result = GraphQLRequestResult(
            request = GraphQLRequest(operationName),
            outcome = outcome,
            durationMillis = durationMillis,
            errorType = error?.let { it::class.simpleName },
        )
        observers.value.forEach { observer ->
            try {
                observer.onComplete(result)
            } catch (_: Throwable) {
                // Telemetry is explicitly best effort and cannot change application request behavior.
            }
        }
    }
}
