@file:OptIn(InternalDI::class)

package bosca.graphql

import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.connection
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.security.service.AuthenticationContext
import bosca.server.BoscaApplication
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

private object DataLoaderConnectionSchemaRoot : SchemaRoot {
    override val query: QueryRoot = object : QueryRoot {}
    override val mutation: MutationRoot = object : MutationRoot {}
    override val subscription: SubscriptionRoot = object : SubscriptionRoot {}
}

private class DataLoaderConnectionGraphQLService : GraphQLService(DataLoaderConnectionSchemaRoot, false) {
    var batchConnection: ConnectionManager? = null
        private set

    override val dispatchersRegistrar: DispatchersRegistrar = object : DispatchersRegistrar {
        override suspend fun dispatchers() = emptyMap<String, Dispatcher>()
    }

    override suspend fun initialize(builder: RuntimeWiringBuilder) {
        builder.type("Query") {
            field("labels") { context ->
                val loader = context.dataLoaderRegistry.getOrPutLoader<String, String>("labels") { keys, _ ->
                    batchConnection = connection()
                    keys.map { "label-$it" }
                }
                loader.loadMany(listOf("1", "2"))
            }
        }
    }
}

class GraphQLServiceDataLoaderConnectionTest {
    private lateinit var connectionPool: ConnectionPool

    @BeforeTest
    fun setUp() = runTest {
        ProviderRegistry.clear()

        connectionPool = mockk()
        every { connectionPool.connection() } answers { ConnectionManager(connectionPool) }

        provides<ConnectionPool> { connectionPool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<BoscaApplication> {
            mockk {
                every { developmentMode } returns false
            }
        }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("GraphQLServiceDataLoaderConnectionTest") }

        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                scalar UUID
                type Query { labels: [String!]! }
            """.trimIndent()
        })
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `batch dispatch has a connection manager`() = runTest {
        val service = DataLoaderConnectionGraphQLService()

        val result = service.execute(
            AuthenticationContext(null, null),
            GraphQLRequest(query = "{ labels }"),
        ).jsonObject

        assertFalse("errors" in result, result.toString())
        assertNotNull(service.batchConnection)
        assertEquals(
            listOf("label-1", "label-2"),
            result.getValue("data").jsonObject.getValue("labels").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
