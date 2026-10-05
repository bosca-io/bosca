@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.jmx

import bosca.cache.Cache
import bosca.cache.CacheManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.middleware.AuthMiddleware
import bosca.server.middleware.CallMiddleware
import bosca.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class JmxTestModule : BoscaApplicationModule {
    override suspend fun install(application: BoscaApplication) = Unit
}

private class JmxTestMiddleware : CallMiddleware

private class JmxTestAuthMiddleware : AuthMiddleware {
    override suspend fun authenticate(call: bosca.server.ServerCall, authConfig: bosca.server.routing.AuthConfig?) = Unit
}

class JmxModuleTest {

    private fun createApp(): BoscaApplication {
        val config = mockk<ApplicationConfig>(relaxed = true)
        every { config.propertyOrNull(any()) } returns null
        return BoscaApplication(config)
    }

    private val objectName = ObjectName(JmxModule.OBJECT_NAME)

    private fun cleanup() {
        val server = ManagementFactory.getPlatformMBeanServer()
        if (server.isRegistered(objectName)) {
            server.unregisterMBean(objectName)
        }
    }

    @AfterTest
    fun teardown() {
        cleanup()
        ProviderRegistry.clear()
    }

    @Test
    fun `JmxModule registers MBean on platform server`() = runTest {
        cleanup()
        val app = createApp()
        app.install(JmxModule(port = 8080))

        val server = ManagementFactory.getPlatformMBeanServer()
        assertTrue(server.isRegistered(objectName))
        cleanup()
    }

    @Test
    fun `MBean reports correct port`() = runTest {
        cleanup()
        val app = createApp()
        app.install(JmxModule(port = 9090))

        val server = ManagementFactory.getPlatformMBeanServer()
        val port = server.getAttribute(objectName, "Port") as Int
        assertEquals(9090, port)
        cleanup()
    }

    @Test
    fun `MBean lists installed modules`() = runTest {
        cleanup()
        val app = createApp()
        app.install(JmxModule(port = 8080))

        val server = ManagementFactory.getPlatformMBeanServer()
        val modules = server.getAttribute(objectName, "InstalledModules") as Array<*>
        assertTrue(modules.contains("JmxModule"))
        cleanup()
    }

    @Test
    fun `MBean reports registered HTTP routes`() = runTest {
        cleanup()
        val app = createApp()
        app.routing {
            get("/test") {}
            post("/api/data") {}
        }
        app.install(JmxModule(port = 8080))

        val server = ManagementFactory.getPlatformMBeanServer()
        val routes = server.getAttribute(objectName, "HttpRoutes") as Array<*>
        assertTrue(routes.contains("GET /test"))
        assertTrue(routes.contains("POST /api/data"))
        cleanup()
    }

    @Test
    fun `MBean reports nested routes with full paths`() = runTest {
        cleanup()
        val app = createApp()
        app.routing {
            route("/api") {
                route("/v1") {
                    get("/users") {}
                }
            }
        }
        app.install(JmxModule(port = 8080))

        val server = ManagementFactory.getPlatformMBeanServer()
        val routes = server.getAttribute(objectName, "HttpRoutes") as Array<*>
        assertTrue(routes.contains("GET /api/v1/users"))
        cleanup()
    }

    @Test
    fun `MBean reports correct total route count`() = runTest {
        cleanup()
        val app = createApp()
        app.routing {
            get("/a") {}
            post("/b") {}
            webSocket("/ws") {}
        }
        app.install(JmxModule(port = 8080))

        val server = ManagementFactory.getPlatformMBeanServer()
        val count = server.getAttribute(objectName, "TotalRouteCount") as Int
        assertEquals(3, count)
        cleanup()
    }

    @Test
    fun `MBean reports worker thread count`() = runTest {
        cleanup()
        val app = createApp()
        app.install(JmxModule(port = 8080, workerThreadCount = 16))

        val server = ManagementFactory.getPlatformMBeanServer()
        val threads = server.getAttribute(objectName, "WorkerThreadCount") as Int
        assertEquals(16, threads)
        cleanup()
    }

    @Test
    fun `BoscaServer directly reports routes modules middleware providers and resources`() = runTest {
        ProviderRegistry.clear()
        val app = createApp()
        app.install(JmxTestModule())
        app.install(JmxTestMiddleware())
        app.installAuth(JmxTestAuthMiddleware())
        app.freezeMiddleware()
        val staticDirectory = Files.createTempDirectory("bosca-jmx-static").toFile()
        app.routing {
            get("/http") {}
            webSocket("/socket") {}
            sse("/events") {}
            staticFiles("/assets", staticDirectory)
            staticResources("/resources", "static")
        }

        val pool = mockk<ConnectionPool>()
        every { pool.name } returns "primary"
        every { pool.maxConnections } returns 10
        every { pool.createdConnections } returns 3
        every { pool.activeConnections } returns 1
        every { pool.hasAvailableConnections } returns true
        provides<ConnectionPool> { pool }

        val cache = mockk<Cache<Any>>()
        every { cache.estimatedSize } returns 42
        val cacheManager = mockk<CacheManager>()
        every { cacheManager.cacheNames } returns setOf("metadata")
        coEvery { cacheManager.getCache<Any>("metadata") } returns cache
        provides<CacheManager> { cacheManager }

        val server = BoscaServer(app, 8181, 12)
        assertEquals(8181, server.port)
        assertTrue(server.uptimeMillis >= 0)
        assertTrue(server.uptime.matches(Regex("\\d+h \\d+m \\d+s")))
        assertFalse(server.developmentMode)
        assertEquals(12, server.workerThreadCount)
        assertTrue(server.availableProcessors > 0)
        assertTrue(server.installedModules.contentEquals(arrayOf("JmxTestModule")))
        assertTrue(server.httpRoutes.contentEquals(arrayOf("GET /http")))
        assertTrue(server.webSocketRoutes.contentEquals(arrayOf("/socket")))
        assertTrue(server.sseRoutes.contentEquals(arrayOf("/events")))
        assertTrue(server.staticRoutes.contentEquals(arrayOf("classpath:/resources", "filesystem:/assets")))
        assertEquals(5, server.totalRouteCount)
        assertEquals("primary: max=10 created=3 active=1 available=true", server.databasePoolStats)
        assertEquals("metadata: estimatedSize=42", server.cacheStats)
        assertTrue(server.typeProviderCount > 0)
        assertTrue(server.namedProviderCount >= 0)
        assertEquals(server.typeProviderCount + server.namedProviderCount, server.totalProviderCount)
        assertTrue(server.middleware.contentEquals(arrayOf("JmxTestMiddleware")))
        assertTrue(server.authMiddleware.contentEquals(arrayOf("JmxTestAuthMiddleware")))
    }

    @Test
    fun `BoscaServer resource statistics handle empty and failing providers`() {
        ProviderRegistry.clear()
        val server = BoscaServer(createApp(), 8080, 1)
        assertEquals("(no pools)", server.databasePoolStats)
        assertEquals("(error collecting stats)", server.cacheStats)

        ProviderRegistry.clear()
        provides<ConnectionPool> { error("pool unavailable") }
        provides<CacheManager> { error("cache unavailable") }
        assertEquals("(error collecting stats)", server.databasePoolStats)
        assertEquals("(error collecting stats)", server.cacheStats)
    }
}
