package bosca.initialization

import bosca.cache.CacheModule
import bosca.counter.CounterModule
import bosca.cache.CacheManager
import bosca.cdn.CdnModule
import bosca.configuration.NatsModule
import bosca.configuration.RedisModule
import bosca.db.ConnectionConfig.Companion.get
import bosca.db.ConnectionConfig.Companion.getOrNull
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.migrations.Migration
import bosca.db.migrations.Migrations
import bosca.db.use
import bosca.di.CoreProviderRegistrar
import bosca.di.ObjectProvider
import bosca.di.ProviderRegistrar
import bosca.di.ProviderRegistry
import bosca.di.ProviderRegistry.register
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.di.provideBlockingNoSuspend
import bosca.di.provideProvider
import bosca.di.provides
import bosca.graphql.GraphQLService
import bosca.http.HttpModule
import bosca.server.BoscaApplication
import bosca.server.BoscaApplicationModule
import bosca.server.HttpStatusCode
import bosca.telemetry.MonitoringModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.seconds

@Serializable
data class ReadyResponse(
    val status: String
)

@Serializable
data class CacheResource(
    val name: String
)

@Serializable
data class DatabaseResource(
    val key: String,
    val maxConnections: Int,
    val createdConnections: Int,
    val activeConnections: Int,
    val hasAvailableConnections: Boolean
)

@Serializable
data class HealthResponse(
    val databases: List<DatabaseResource>,
    val caches: List<CacheResource>,
    val ok: Boolean,
)

private open class TrinoObjectProvider(private val name: String) : ObjectProvider<ConnectionPool> {

    override val type: KClass<ConnectionPool> = ConnectionPool::class

    private val pool by lazy {
        ConnectionPool(ConnectionFactoryImpl(provideBlockingNoSuspend<BoscaApplication>().get(name), name))
    }

    override val exists: Boolean
        get() = provideBlockingNoSuspend<BoscaApplication>().getOrNull(name) != null

    override suspend fun get() = pool
}

private class AdminTrinoObjectProvider : TrinoObjectProvider("trino-admin")

private class ReadOnlyTrinoObjectProvider : TrinoObjectProvider("trino-readonly")


/**
 * Core initialization module that bootstraps the application's infrastructure by
 * registering DI providers, installing infrastructure sub-modules (monitoring, Redis,
 * NATS, cache, CDN, HTTP), running database migrations, and setting up health and
 * readiness endpoints.
 *
 * This module should be installed early in the startup sequence since most other
 * modules depend on the infrastructure it configures.
 */
class InitializeModule(
    private val enableConnectionPool: Boolean = true,
    private val providers: Array<out ProviderRegistrar> = emptyArray()
) : BoscaApplicationModule {

    @OptIn(InternalDI::class)
    override suspend fun install(application: BoscaApplication) = with(application) {
        provides(singleton = true) { ConnectionPool(provide()) }

        register(CoreProviderRegistrar())
        register(*providers)

        install(MonitoringModule())
        install(RedisModule())
        install(NatsModule())
        install(CacheModule())
        install(CounterModule())
        install(CdnModule())
        install(HttpModule())

        if (enableConnectionPool) {
            try {
                val migrations = ProviderRegistry.findAll(Migration::class).map { it.get() }
                provide<Migrations>().migrate(migrations)
            } catch (e: Throwable) {
                log.error("Failed to run database migrations: ${e.message}", e)
                Runtime.getRuntime().halt(1)
            }
            onShutdown {
                for (provider in ProviderRegistry.findAll(ConnectionPool::class)) {
                    runCatching {
                        provider.get().close()
                    }
                }
            }
        }

        register(ConnectionPool::class, AdminTrinoObjectProvider(), "trino-admin", true)
        register(ConnectionPool::class, ReadOnlyTrinoObjectProvider(), "trino-readonly", true)

        routing {
            // Liveness: answers "is the process wedged?", nothing more. It must NOT touch the
            // connection pool, the cache, or any other shared/contended resource — a liveness
            // probe that depends on the database makes Kubernetes restart pods whenever the DB
            // is merely slow or the pool is saturated under load, which drops in-flight work and
            // makes the overload worse. Dependency health belongs on /ready (readiness) and
            // /health (startup), which can fail without triggering a restart.
            get("/api/v1/live") {
                call.respond(HttpStatusCode.OK, ReadyResponse("live"))
            }
            get("/api/v1/ready") {
                val serviceProvider = provideProvider<GraphQLService>()
                if (!serviceProvider.exists) {
                    call.respond(HttpStatusCode.OK, ReadyResponse("ready"))
                    return@get
                }

                val connection = provide<ConnectionPool>().connection()
                try {
                    connection.use {
                        withTimeout(15.seconds) {
                            it.useStatement("select 1") {
                                it.executeQuery().use {
                                    if (it.next()) require(it.getInt(1) == 1)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Dependency timeouts fail the probe; cancellation of the request propagates.
                    currentCoroutineContext().ensureActive()
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    log.error("Failed to check database connection: ${e.message}", e)
                    call.respond(HttpStatusCode.ServiceUnavailable, ReadyResponse("primary connection pool unavailable"))
                    return@get
                } finally {
                    connection.release()
                }

                val service = serviceProvider.get()
                if (service.isReady()) {
                    call.respond(HttpStatusCode.OK, ReadyResponse("ready"))
                } else {
                    call.respond(HttpStatusCode.ServiceUnavailable, ReadyResponse("not ready"))
                }
            }
            get("/api/v1/health") {
                val cacheManager = provide<CacheManager>()

                val connectionPools = ProviderRegistry.findAll(ConnectionPool::class).mapNotNull {
                    if (!it.exists) return@mapNotNull null
                    val pool = it.get()
                    DatabaseResource(
                        key = pool.name,
                        maxConnections = pool.maxConnections,
                        createdConnections = pool.createdConnections,
                        activeConnections = pool.activeConnections,
                        hasAvailableConnections = pool.hasAvailableConnections,
                    )
                }

                val connection = provide<ConnectionPool>().connection()
                try {
                    connection.use {
                        withTimeout(15.seconds) {
                            it.useStatement("select 1") {
                                it.executeQuery().use {
                                    if (it.next()) require(it.getInt(1) == 1)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Dependency timeouts fail the probe; cancellation of the request propagates.
                    currentCoroutineContext().ensureActive()
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    log.error("Failed to check database connection: ${e.message}", e)
                    call.respond(HttpStatusCode.ServiceUnavailable, ReadyResponse("primary connection pool unavailable"))
                    return@get
                } finally {
                    connection.release()
                }

                call.respond(
                    HealthResponse(
                        databases = connectionPools,
                        caches = cacheManager.cacheNames.map {
                            CacheResource(
                                name = it
                            )
                        },
                        ok = true,
                    )
                )
            }
        }
    }
}
