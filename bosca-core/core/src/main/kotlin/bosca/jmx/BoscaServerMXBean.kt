package bosca.jmx

/**
 * JMX MXBean interface exposing comprehensive server introspection for the Bosca
 * application. Accessible via jconsole or any JMX client under the object name
 * `bosca:type=Server`.
 *
 * All attributes are read-only and provide visibility into server configuration,
 * installed modules, registered routes, database connection pool health, cache
 * statistics, DI provider counts, and the middleware stack.
 *
 * Complex statistics (database pools, caches) are exposed as formatted strings
 * rather than [javax.management.openmbean.TabularData] to ensure compatibility
 * with GraalVM native images and JDK module-based reflection.
 */
interface BoscaServerMXBean {

    // --- Server info ---

    /** The TCP port the server is listening on. */
    val port: Int

    /** Server uptime in milliseconds since the MBean was registered. */
    val uptimeMillis: Long

    /** Human-readable uptime string (e.g., "2h 15m 30s"). */
    val uptime: String

    /** Whether the server is running in development mode. */
    val developmentMode: Boolean

    /** Number of Netty worker threads. */
    val workerThreadCount: Int

    /** Number of available processors as seen by the JVM. */
    val availableProcessors: Int

    // --- Modules ---

    /** Names of all installed BoscaApplicationModule instances. */
    val installedModules: Array<String>

    // --- Routes ---

    /** All registered HTTP routes as "METHOD /path" strings. */
    val httpRoutes: Array<String>

    /** All registered WebSocket endpoint paths. */
    val webSocketRoutes: Array<String>

    /** All registered SSE endpoint paths. */
    val sseRoutes: Array<String>

    /** All registered static content mount points as "type:path" strings. */
    val staticRoutes: Array<String>

    /** Total count of all registered routes across all types. */
    val totalRouteCount: Int

    // --- Database pool stats ---

    /**
     * Database connection pool statistics as a formatted multi-line string.
     * Each line: "name: max=N created=N active=N available=true/false".
     */
    val databasePoolStats: String

    // --- Cache stats ---

    /**
     * Cache statistics as a formatted multi-line string.
     * Each line: "name: estimatedSize=N".
     */
    val cacheStats: String

    // --- DI ---

    /** Number of type-based providers registered in ProviderRegistry. */
    val typeProviderCount: Int

    /** Number of named providers registered in ProviderRegistry. */
    val namedProviderCount: Int

    /** Total DI provider count (type + named). */
    val totalProviderCount: Int

    // --- Middleware ---

    /** Class names of installed request middleware, in execution order. */
    val middleware: Array<String>

    /** Class names of installed authentication middleware. */
    val authMiddleware: Array<String>
}
