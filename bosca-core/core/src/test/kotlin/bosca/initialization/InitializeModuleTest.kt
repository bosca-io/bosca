@file:OptIn(bosca.di.annotation.InternalDI::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package bosca.initialization

import bosca.cache.CacheManager
import bosca.di.ProviderRegistry
import bosca.di.providerMissing
import bosca.di.provides
import bosca.graphql.GraphQLConnectionInitAuthenticator
import bosca.graphql.GraphQLService
import bosca.server.BoscaApplication
import bosca.server.HttpMethod
import bosca.server.Parameters
import bosca.server.ServerCall
import bosca.server.ServerRequest
import bosca.server.ServerResponse
import bosca.server.config.ApplicationConfig
import bosca.server.routing.RoutingContext
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.PoolConnection
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.opentelemetry.api.GlobalOpenTelemetry
import kotlinx.coroutines.test.runTest
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InitializeModuleTest {

    private lateinit var application: BoscaApplication

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        GlobalOpenTelemetry.resetForTest()
        System.setProperty("otel.traces.exporter", "none")
        System.setProperty("otel.logs.exporter", "none")
        application = BoscaApplication(ApplicationConfig.load("".byteInputStream()))
        providerMissing<GraphQLConnectionInitAuthenticator>()
        providerMissing<GraphQLService>()
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
        GlobalOpenTelemetry.resetForTest()
        System.clearProperty("otel.traces.exporter")
        System.clearProperty("otel.logs.exporter")
    }

    private fun context(responses: MutableList<Any>): ChannelHandlerContext {
        val context = mockk<ChannelHandlerContext>(relaxed = true)
        val channel = mockk<Channel>(relaxed = true)
        val future = mockk<ChannelFuture>(relaxed = true)
        every { context.channel() } returns channel
        every { context.alloc() } returns UnpooledByteBufAllocator.DEFAULT
        every { channel.isActive } returns true
        every { channel.isWritable } returns true
        every { context.writeAndFlush(capture(responses)) } returns future
        every { context.write(any()) } returns future
        return context
    }

    private fun call(responses: MutableList<Any>): ServerCall = ServerCall(
        mockk<ServerRequest>(relaxed = true),
        ServerResponse(context(responses)),
        Parameters.Empty,
        application,
    )

    private suspend fun execute(path: String): List<DefaultFullHttpResponse> {
        val responses = mutableListOf<Any>()
        val call = call(responses)
        val route = requireNotNull(application.router.resolve(HttpMethod.Get, path))
        route.handler(RoutingContext(call, application))
        return responses.map { it as DefaultFullHttpResponse }
    }

    private fun installDatabase(graphQLReady: Boolean, queryFailure: Throwable? = null) {
        val resultSet = mockk<ResultSet>(relaxed = true)
        every { resultSet.next() } returns true
        every { resultSet.getInt(1) } returns 1
        val statement = mockk<PreparedStatement>(relaxed = true)
        if (queryFailure == null) {
            every { statement.executeQuery() } returns resultSet
        } else {
            every { statement.executeQuery() } throws queryFailure
        }
        val pooled = mockk<PoolConnection>(relaxed = true)
        every { pooled.prepareStatement("select 1") } returns statement
        val pool = mockk<ConnectionPool>()
        every { pool.connection() } answers { ConnectionManager(pool) }
        coEvery { pool.obtainConnection(any(), any()) } returns pooled
        coEvery { pool.releaseConnection(pooled) } returns Unit
        every { pool.name } returns "primary"
        every { pool.maxConnections } returns 10
        every { pool.createdConnections } returns 2
        every { pool.activeConnections } returns 1
        every { pool.hasAvailableConnections } returns true
        provides<ConnectionPool>(overrideExisting = true) { pool }

        val service = mockk<GraphQLService>()
        every { service.isReady() } returns graphQLReady
        provides<GraphQLService>(overrideExisting = true) { service }
        val cache = mockk<CacheManager>()
        every { cache.cacheNames } returns setOf("metadata", "collections")
        provides<CacheManager>(overrideExisting = true) { cache }
    }

    @Test
    fun `initialization installs infrastructure and serves live and provider-free ready probes`() = runTest {
        application.install(InitializeModule(enableConnectionPool = false))

        assertTrue(application.modules.keys.any { it.simpleName == "MonitoringModule" })
        assertTrue(application.modules.keys.any { it.simpleName == "HttpModule" })
        assertEquals(4, application.middleware.filter { it::class.simpleName?.contains("Middleware") == true }.size)
        assertEquals(200, execute("/api/v1/live").single().status().code())
        assertEquals(200, execute("/api/v1/ready").single().status().code())
    }

    @Test
    fun `ready probe reports GraphQL readiness after validating primary database`() = runTest {
        application.install(InitializeModule(enableConnectionPool = false))

        installDatabase(graphQLReady = true)
        var response = execute("/api/v1/ready").single()
        assertEquals(200, response.status().code())
        assertTrue(response.content().toString(Charsets.UTF_8).contains("ready"))

        installDatabase(graphQLReady = false)
        response = execute("/api/v1/ready").single()
        assertEquals(503, response.status().code())
        assertTrue(response.content().toString(Charsets.UTF_8).contains("not ready"))
    }

    @Test
    fun `ready and health probes report database failures`() = runTest {
        application.install(InitializeModule(enableConnectionPool = false))
        installDatabase(graphQLReady = true, queryFailure = IllegalStateException("database unavailable"))

        var response = execute("/api/v1/ready").single()
        assertEquals(503, response.status().code())
        assertTrue(response.content().toString(Charsets.UTF_8).contains("primary connection pool unavailable"))

        response = execute("/api/v1/health").single()
        assertEquals(503, response.status().code())
    }

    @Test
    fun `health probe reports pool statistics and cache inventory`() = runTest {
        application.install(InitializeModule(enableConnectionPool = false))
        installDatabase(graphQLReady = true)

        val response = execute("/api/v1/health").single()
        val body = response.content().toString(Charsets.UTF_8)
        assertEquals(200, response.status().code())
        assertTrue(body.contains("primary"))
        assertTrue(body.contains("metadata"))
        assertTrue(body.contains("collections"))
        assertTrue(body.contains("\"ok\":true"))
    }

    @Test
    fun `health response data models expose equality and computed values`() {
        val database = DatabaseResource("primary", 10, 2, 1, true)
        assertEquals(database, database.copy())
        assertFalse(database.equals("database"))
        assertEquals(CacheResource("metadata"), CacheResource("metadata"))
        assertEquals(ReadyResponse("ready"), ReadyResponse("ready"))
        val health = HealthResponse(listOf(database), listOf(CacheResource("metadata")), true)
        assertEquals(health, health.copy())
        assertEquals(health.hashCode(), health.copy().hashCode())
    }
}
