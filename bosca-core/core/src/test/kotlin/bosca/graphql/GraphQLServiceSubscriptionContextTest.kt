@file:OptIn(InternalDI::class)

package bosca.graphql

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.cache.requestCache
import bosca.db.connectionLimiter
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.security.service.AuthenticationProviders
import bosca.server.BoscaApplication
import bosca.server.ServerCall
import bosca.telemetry.Tracing
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object SubscriptionContextSchemaRoot : SchemaRoot {
    override val query: QueryRoot = object : QueryRoot {}
    override val mutation: MutationRoot = object : MutationRoot {}
    override val subscription: SubscriptionRoot = object : SubscriptionRoot {}
}

private class SubscriptionContextGraphQLService : GraphQLService(SubscriptionContextSchemaRoot, false) {
    var requestContextsObserved = false
        private set

    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        builder.type("Subscription") {
            field("ticks") {
                flow {
                    requestCache()
                    connectionLimiter()
                    requestContextsObserved = true
                    emit(1)
                    emit(2)
                }
            }
        }
    }
}

class GraphQLServiceSubscriptionContextTest {

    @BeforeTest
    fun setUp() = runTest {
        ProviderRegistry.clear()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("GraphQLServiceSubscriptionContextTest") }
        provides<BoscaApplication> { mockk(relaxed = true) }

        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                scalar UUID
                type Query { ping: String }
                type Subscription { ticks: Int! }
            """.trimIndent()
        })
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `subscription emits with request contexts installed`() = runTest {
        val application = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = application)
        val service = SubscriptionContextGraphQLService()

        val results = service.subscribe(call, GraphQLRequest(query = "subscription { ticks }")).toList()

        assertEquals(
            listOf(1, 2),
            results.map { it.jsonObject.getValue("data").jsonObject.getValue("ticks").jsonPrimitive.int },
        )
        assertTrue(service.requestContextsObserved)
    }

    @Test
    fun `subscription without a query emits a GraphQL error`() = runTest {
        val application = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = application)
        val service = SubscriptionContextGraphQLService()

        val result = service.subscribe(call, GraphQLRequest()).single().jsonObject

        assertTrue("errors" in result)
    }

    @Test
    fun `named subscription preserves an existing trace and explicit variables`() = runTest {
        val application = mockk<BoscaApplication>(relaxed = true)
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = application)
        val service = SubscriptionContextGraphQLService()

        val results = Tracing.withTrace(Tracing.newTraceId()) {
            service.subscribe(
                call,
                GraphQLRequest(
                    query = "subscription Named { ticks }",
                    operationName = "Named",
                    variables = buildJsonObject { },
                ),
            ).toList()
        }

        assertEquals(2, results.size)
    }
}
