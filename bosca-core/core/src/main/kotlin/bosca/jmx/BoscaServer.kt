package bosca.jmx

import bosca.cache.CacheManager
import bosca.db.ConnectionPool
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.server.BoscaApplication
import bosca.server.routing.RouteType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

/**
 * MXBean implementation that collects and exposes Bosca server metrics for JMX
 * introspection. Holds references to the [BoscaApplication] and server metadata
 * captured at registration time.
 *
 * Database pool and cache statistics are fetched on-demand from the DI container
 * when JMX attribute getters are called. Suspend-based providers are bridged to
 * blocking calls with a short timeout to prevent JMX threads from hanging.
 */
class BoscaServer(
    private val application: BoscaApplication,
    private val serverPort: Int,
    private val nettyWorkerThreadCount: Int,
) : BoscaServerMXBean {

    private val startTimeMillis = System.currentTimeMillis()
    private val log = LoggerFactory.getLogger(BoscaServer::class.java)

    override val port: Int get() = serverPort

    override val uptimeMillis: Long get() = System.currentTimeMillis() - startTimeMillis

    override val uptime: String get() {
        val totalSeconds = uptimeMillis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${hours}h ${minutes}m ${seconds}s"
    }

    override val developmentMode: Boolean get() = application.developmentMode

    override val workerThreadCount: Int get() = nettyWorkerThreadCount

    override val availableProcessors: Int get() = Runtime.getRuntime().availableProcessors()

    override val installedModules: Array<String> get() =
        application.modules.keys.mapNotNull { it.simpleName }.sorted().toTypedArray()

    private val cachedRouteDescriptions by lazy { application.router.collectRouteDescriptions() }

    override val httpRoutes: Array<String> get() =
        cachedRouteDescriptions
            .filter { it.type == RouteType.HTTP }
            .map { "${it.method} ${it.path}" }
            .sorted()
            .toTypedArray()

    override val webSocketRoutes: Array<String> get() =
        cachedRouteDescriptions
            .filter { it.type == RouteType.WEBSOCKET }
            .map { it.path }
            .sorted()
            .toTypedArray()

    override val sseRoutes: Array<String> get() =
        cachedRouteDescriptions
            .filter { it.type == RouteType.SSE }
            .map { it.path }
            .sorted()
            .toTypedArray()

    override val staticRoutes: Array<String> get() =
        cachedRouteDescriptions
            .filter { it.type == RouteType.STATIC }
            .map { "${it.staticSourceType}:${it.path}" }
            .sorted()
            .toTypedArray()

    override val totalRouteCount: Int get() =
        cachedRouteDescriptions.size

    @OptIn(InternalDI::class)
    override val databasePoolStats: String get() {
        try {
            val pools = ProviderRegistry.findAll(ConnectionPool::class)
            return pools.joinToString("\n") { provider ->
                val pool = runBlocking { withTimeout(3.seconds) { provider.get() } }
                "${pool.name}: max=${pool.maxConnections} created=${pool.createdConnections} " +
                    "active=${pool.activeConnections} available=${pool.hasAvailableConnections}"
            }.ifEmpty { "(no pools)" }
        } catch (e: Exception) {
            log.warn("Failed to collect database pool stats for JMX", e)
            return "(error collecting stats)"
        }
    }

    @OptIn(InternalDI::class)
    override val cacheStats: String get() {
        try {
            val cacheManager = runBlocking {
                withTimeout(3.seconds) {
                    bosca.di.provide<CacheManager>()
                }
            }
            return cacheManager.cacheNames.joinToString("\n") { name ->
                val cache = runBlocking {
                    withTimeout(3.seconds) {
                        cacheManager.getCache<Any>(name)
                    }
                }
                "$name: estimatedSize=${cache.estimatedSize}"
            }.ifEmpty { "(no caches)" }
        } catch (e: Exception) {
            log.warn("Failed to collect cache stats for JMX", e)
            return "(error collecting stats)"
        }
    }

    @OptIn(InternalDI::class)
    override val typeProviderCount: Int get() = ProviderRegistry.typeProviderCount

    @OptIn(InternalDI::class)
    override val namedProviderCount: Int get() = ProviderRegistry.namedProviderCount

    override val totalProviderCount: Int get() = typeProviderCount + namedProviderCount

    override val middleware: Array<String> get() =
        application.middleware.map { it::class.simpleName ?: it::class.java.name }.toTypedArray()

    override val authMiddleware: Array<String> get() =
        application.authMiddleware.map { it::class.simpleName ?: it::class.java.name }.toTypedArray()
}
