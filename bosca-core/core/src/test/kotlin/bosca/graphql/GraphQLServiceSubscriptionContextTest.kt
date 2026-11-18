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
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private object SubscriptionContextSchemaRoot : SchemaRoot {
    override val query: QueryRoot = object : QueryRoot {}
    override val mutation: MutationRoot = object : MutationRoot {}
    override val subscription: SubscriptionRoot = object : SubscriptionRoot {}
}

private class SubscriptionContextGraphQLService(
    private val ticks: Flow<Int> = flowOf(1, 2),
) : GraphQLService(SubscriptionContextSchemaRoot, false) {
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
                    emitAll(ticks)
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
    fun `cancelling an active subscription cleans up without logging an execution failure`() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val service = SubscriptionContextGraphQLService(flow {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        })
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = mockk(relaxed = true))

        withServiceLog { events ->
            val subscription = launch(start = CoroutineStart.LAZY) {
                service.subscribe(call, GraphQLRequest(query = "subscription { ticks }")).collect()
            }
            subscription.start()
            started.await()
            subscription.cancelAndJoin()

            assertTrue(subscription.isCancelled)
            assertTrue(stopped.isCompleted, "Cancellation must reach the subscription source")
            assertTrue(events.isEmpty(), "Cancellation must not log an execution failure: ${events.map { it.formattedMessage }}")
        }
    }

    @Test
    fun `subscription source failure is logged and propagated`() = runTest {
        val failure = IllegalStateException("Subscription source failed")
        val service = SubscriptionContextGraphQLService(flow { throw failure })
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = mockk(relaxed = true))

        withServiceLog { events ->
            val thrown = assertFailsWith<IllegalStateException> {
                service.subscribe(call, GraphQLRequest(query = "subscription { ticks }")).collect()
            }
            assertEquals(failure.message, thrown.message)
            assertEquals(1, events.size)
            assertEquals(Level.ERROR, events.single().level)
            assertEquals(failure.message, events.single().throwableProxy.message)
        }
    }

    @Test
    fun `subscription source timeout is logged and propagated while caller remains active`() = runTest {
        val service = SubscriptionContextGraphQLService(flow {
            withTimeout(10) { awaitCancellation() }
        })
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = mockk(relaxed = true))

        withServiceLog { events ->
            val thrown = assertFailsWith<TimeoutCancellationException> {
                service.subscribe(call, GraphQLRequest(query = "subscription { ticks }")).collect()
            }

            assertTrue(currentCoroutineContext().isActive, "An internal timeout must not cancel the caller")
            assertEquals(1, events.size)
            assertEquals(Level.ERROR, events.single().level)
            assertEquals(TimeoutCancellationException::class.java.name, events.single().throwableProxy.className)
            assertEquals(thrown.message, events.single().throwableProxy.message)
        }
    }

    @Test
    fun `collector timeout cancels the subscription without logging an execution failure`() = runTest {
        val stopped = CompletableDeferred<Unit>()
        val service = SubscriptionContextGraphQLService(flow {
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        })
        val call = ServerCall(mockk(relaxed = true), mockk(relaxed = true), application = mockk(relaxed = true))

        withServiceLog { events ->
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) {
                    service.subscribe(call, GraphQLRequest(query = "subscription { ticks }")).collect()
                }
            }

            assertTrue(stopped.isCompleted, "The collector timeout must cancel the subscription source")
            assertTrue(events.isEmpty(), "Collector cancellation must not log an execution failure")
        }
    }

    private suspend fun withServiceLog(block: suspend (List<ILoggingEvent>) -> Unit) {
        val logger = LoggerFactory.getLogger(GraphQLService::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.ERROR
        logger.addAppender(appender)
        try {
            block(appender.list)
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
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
