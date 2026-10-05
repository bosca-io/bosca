package bosca.analytics.experimentation

import bosca.analytics.delivery.AnalyticsLogger
import bosca.analytics.delivery.defaultAnalyticsLogger
import bosca.analytics.experimentation.persistence.FeatureFlagCacheKey
import bosca.analytics.experimentation.persistence.FeatureFlagCacheStore
import bosca.analytics.experimentation.persistence.NoOpFeatureFlagCacheStore
import bosca.analytics.platform.currentDevice
import bosca.core.analytics.InstallationIdProvider
import bosca.graphql.client.GraphQLClientException
import bosca.graphql.client.GraphQLResponse
import bosca.graphql.client.GraphQLSubscriptionClient
import bosca.graphql.client.KtorGraphQLClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureFlagClientTest {
    @Test
    fun `initialize uses generated operation with analytics identity and device`() = runTest {
        var requestBody = ""
        val client = featureClient(
            http = HttpClient(MockEngine { request ->
                requestBody = (request.body as TextContent).text
                respond(EVALUATIONS, HttpStatusCode.OK, JSON_HEADERS)
            }),
            provider = InstallationIdProvider { "analytics-installation" },
        )

        client.initialize()

        assertTrue(requestBody.contains("\"operationName\":\"EvaluateAllFlags\""))
        assertTrue(requestBody.contains("\"installationId\":\"analytics-installation\""))
        assertTrue(requestBody.contains("\"device\""))
        assertTrue(requestBody.contains("\"platform\":\"${currentDevice("analytics-installation").platform}\""))
        assertEquals(true, client.getBoolean("new-ui"))
        assertEquals("blue", client.getString("theme"))
        assertEquals(2.5, client.getNumber("weight"))
        assertEquals(JsonObject(mapOf("size" to JsonPrimitive("large"))), client.getJson("config"))
        client.close()
    }

    @Test
    fun `cache uses stable application identity and publishes changes`() = runTest {
        val store = RecordingFlagStore()
        val client = featureClient(store = store)
        var changes = 0
        val unsubscribe = client.onChange { changes++ }

        assertEquals(false, client.loadCache())
        store.flags = listOf(FeatureFlag("cached", JsonPrimitive(true)))
        assertEquals(true, client.loadCache())
        assertEquals(FeatureFlagCacheKey("iid", "user-42"), store.loadedKey)
        assertEquals(true, client.getBoolean("cached"))
        assertEquals(listOf("cached"), client.getCache().map { it.flagKey })

        unsubscribe()
        client.restoreCache(listOf(FeatureFlag("cached", JsonPrimitive(false))))
        assertEquals(1, changes)
        assertEquals(false, client.getBoolean("cached"))
        client.close()
    }

    @Test
    fun `initialization accepts empty flags and surfaces transport and GraphQL failures`() = runTest {
        val empty = featureClient(
            http = HttpClient(MockEngine { respond("""{"data":{"featureFlags":{"evaluateAll":[]}}}""", headers = JSON_HEADERS) }),
        )
        empty.initialize()
        assertTrue(empty.flags.value.isEmpty())
        empty.refresh()
        empty.close()

        val failed = featureClient(
            http = HttpClient(MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }),
        )
        assertTrue(assertFailsWith<IllegalStateException> { failed.initialize() }.message.orEmpty().contains("HTTP 503"))
        failed.close()

        val graphql = featureClient(
            http = HttpClient(MockEngine {
                respond("""{"errors":[{"message":"failed"}]}""", headers = JSON_HEADERS)
            }),
        )
        assertFailsWith<GraphQLClientException> { graphql.initialize() }
        graphql.close()
    }

    @Test
    fun `flag refresh persists success and queues typed client failures`() = runTest {
        val logs = mutableListOf<String>()
        val store = RecordingFlagStore()
        var calls = 0
        val client = featureClient(
            http = HttpClient(MockEngine {
                calls++
                when (calls) {
                    1 -> respond(
                        """{"data":{"featureFlags":{"evaluate":{"flagKey":"new-ui","value":false,"variationKey":"off","experimentId":null}}}}""",
                        headers = JSON_HEADERS,
                    )
                    2 -> respond("""{"data":null,"errors":[{"message":"gone"}]}""", headers = JSON_HEADERS)
                    else -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                }
            }),
            store = store,
            logger = AnalyticsLogger { message, _ -> logs += message },
        )

        client.refreshFlag("new-ui")
        assertEquals(false, client.getBoolean("new-ui", true))
        assertEquals("off", client.getFlag("new-ui")?.variationKey)
        assertEquals(listOf("new-ui"), store.savedFlags.map { it.flagKey })
        assertEquals(FeatureFlagCacheKey("iid", "user-42"), store.savedKey)
        client.refreshFlag("missing")
        client.refreshFlag("failed")

        assertEquals(2, logs.count { it.contains("queued for retry") })
        client.close()
    }

    @Test
    fun `typed accessors return defaults for missing and incompatible values`() {
        val client = featureClient()
        client.restoreCache(
            listOf(
                FeatureFlag("object", JsonObject(mapOf("key" to JsonPrimitive("value")))),
                FeatureFlag("text", JsonPrimitive("not-boolean-or-number")),
            ),
        )

        assertEquals(true, client.getBoolean("missing", true))
        assertEquals(false, client.getBoolean("missing"))
        assertEquals(true, client.getBoolean("object", true))
        assertEquals(true, client.getBoolean("text", true))
        assertEquals("fallback", client.getString("missing", "fallback"))
        assertEquals("", client.getString("missing"))
        assertEquals("fallback", client.getString("object", "fallback"))
        assertEquals("not-boolean-or-number", client.getString("text", "fallback"))
        assertEquals(3.5, client.getNumber("missing", 3.5))
        assertEquals(0.0, client.getNumber("missing"))
        assertEquals(3.5, client.getNumber("object", 3.5))
        assertEquals(3.5, client.getNumber("text", 3.5))
        assertNull(client.getJson("missing"))
        assertNull(client.getFlag("missing"))
        assertEquals("object", client.getFlag("object")?.flagKey)
        client.close()
    }

    @Test
    fun `listener failures are isolated while cancellation remains cancellation`() = runTest {
        val logs = mutableListOf<String>()
        val client = featureClient(logger = AnalyticsLogger { message, _ -> logs += message })
        client.onChange { error("listener failed") }
        client.restoreCache(listOf(FeatureFlag("flag", JsonPrimitive(true))))
        assertTrue(logs.any { it.contains("listener failed") })

        val cancelled = featureClient()
        cancelled.onChange { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> {
            cancelled.restoreCache(listOf(FeatureFlag("flag", JsonPrimitive(true))))
        }
        client.close()
        cancelled.close()
    }

    @Test
    fun `realtime consumer counting and no-op defaults are safe`() = runTest {
        val client = featureClient()
        client.addRealtimeListener()
        client.removeRealtimeListener()
        client.removeRealtimeListener()
        client.addRealtimeListener()
        client.initialize()
        client.addRealtimeListener()
        client.removeRealtimeListener()
        client.removeRealtimeListener()
        client.startListening()
        client.stopListening()
        client.close()

        val defaultLogger = defaultAnalyticsLogger()
        defaultLogger.log("message", null)
        defaultLogger.log("message", IllegalStateException("failure"))
        val key = FeatureFlagCacheKey(null, null)
        assertNull(NoOpFeatureFlagCacheStore.load(key))
        NoOpFeatureFlagCacheStore.save(key, emptyList())
    }

    private fun featureClient(
        options: FeatureFlagOptions = FeatureFlagOptions(identity = { "user-42" }, maxReconnectAttempts = 0),
        http: HttpClient = HttpClient(MockEngine { respond(EVALUATIONS, headers = JSON_HEADERS) }),
        store: FeatureFlagCacheStore = NoOpFeatureFlagCacheStore,
        provider: InstallationIdProvider = InstallationIdProvider { "iid" },
        logger: AnalyticsLogger = AnalyticsLogger { _, _ -> },
        subscriptions: GraphQLSubscriptionClient = EmptySubscriptions,
    ) = FeatureFlagClient(
        options = options,
        client = KtorGraphQLClient("https://api.test/graphql", http),
        subscriptions = subscriptions,
        cacheStore = store,
        installationIdProvider = provider,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        logger = logger,
    )

    private class RecordingFlagStore : FeatureFlagCacheStore {
        var flags: List<FeatureFlag>? = null
        var loadedKey: FeatureFlagCacheKey? = null
        var savedKey: FeatureFlagCacheKey? = null
        var savedFlags = emptyList<FeatureFlag>()

        override suspend fun load(key: FeatureFlagCacheKey): List<FeatureFlag>? {
            loadedKey = key
            return flags
        }

        override suspend fun save(key: FeatureFlagCacheKey, flags: List<FeatureFlag>) {
            savedKey = key
            savedFlags = flags
        }
    }

    private object EmptySubscriptions : GraphQLSubscriptionClient {
        override fun subscribe(
            document: String,
            variables: JsonObject?,
            operationName: String?,
        ): Flow<GraphQLResponse> = emptyFlow()
    }

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val EVALUATIONS = """
            {"data":{"featureFlags":{"evaluateAll":[
              {"flagKey":"new-ui","value":true,"variationKey":"on","experimentId":null},
              {"flagKey":"theme","value":"blue","variationKey":"blue","experimentId":null},
              {"flagKey":"weight","value":2.5,"variationKey":"weighted","experimentId":null},
              {"flagKey":"config","value":{"size":"large"},"variationKey":"large","experimentId":null}
            ]}}}
        """
    }
}
