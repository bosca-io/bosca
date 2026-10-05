package bosca.analytics.experimentation

import bosca.analytics.delivery.AnalyticsLogger
import bosca.graphql.client.GraphQLResponse
import bosca.graphql.client.GraphQLSubscriptionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class FeatureFlagRealtimeTest {
    @Test
    fun `generated subscription retries dirty flags and routes typed updates`() = runBlocking {
        val deleted = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        var retried = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val subscriptions = QueueSubscriptions(
            listOf(
                flowOf(response("deleted", "DELETED"), response("changed", "UPDATED")),
            ),
        )
        val realtime = FeatureFlagRealtime(
            options = options(maxReconnectAttempts = 0),
            subscriptions = subscriptions,
            scope = scope,
            logger = AnalyticsLogger { _, _ -> },
            retryPending = { retried++ },
            deleteFlag = { deleted += it },
            refreshFlag = { refreshed += it },
        )

        realtime.start()
        withTimeout(2_000) {
            while (deleted.isEmpty() || refreshed.isEmpty()) delay(1)
        }

        assertEquals(listOf("deleted"), deleted)
        assertEquals(listOf("changed"), refreshed)
        assertEquals(2, retried)
        assertEquals(1, subscriptions.attempts)
        realtime.stop()
        scope.cancel()
    }

    @Test
    fun `subscription failures retry to the configured bound and can restart`() = runBlocking {
        val logs = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val subscriptions = FailingSubscriptions()
        val realtime = FeatureFlagRealtime(
            options = options(maxReconnectAttempts = 1),
            subscriptions = subscriptions,
            scope = scope,
            logger = AnalyticsLogger { message, _ -> logs += message },
            retryPending = {},
            deleteFlag = {},
            refreshFlag = {},
        )

        realtime.start()
        withTimeout(2_000) {
            while (subscriptions.attempts < 2) delay(1)
        }
        assertEquals(2, logs.count { it.contains("subscription failed") })

        realtime.start()
        withTimeout(2_000) {
            while (subscriptions.attempts < 4) delay(1)
        }
        assertTrue(subscriptions.attempts >= 4)
        realtime.stop()
        scope.cancel()
    }

    private fun options(maxReconnectAttempts: Int) = FeatureFlagOptions(
        maxReconnectAttempts = maxReconnectAttempts,
        baseReconnectDelay = 1.milliseconds,
        maxReconnectDelay = 1.milliseconds,
    )

    private fun response(flagKey: String, action: String) = GraphQLResponse(
        data = JSON.parseToJsonElement(
            """{"flagUpdated":{"flagKey":"$flagKey","flagId":"00000000-0000-0000-0000-000000000001","action":"$action"}}""",
        ),
    )

    private class QueueSubscriptions(
        private val flows: List<Flow<GraphQLResponse>>,
    ) : GraphQLSubscriptionClient {
        var attempts = 0

        override fun subscribe(
            document: String,
            variables: JsonObject?,
            operationName: String?,
        ): Flow<GraphQLResponse> = flows[minOf(attempts++, flows.lastIndex)]
    }

    private class FailingSubscriptions : GraphQLSubscriptionClient {
        var attempts = 0

        override fun subscribe(
            document: String,
            variables: JsonObject?,
            operationName: String?,
        ): Flow<GraphQLResponse> = flow {
            attempts++
            error("subscription unavailable")
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
