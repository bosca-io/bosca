package bosca.db

import bosca.db.ConnectionConfig.Companion.get
import bosca.di.ObjectProvider
import bosca.server.BoscaApplication
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Lazily creates the bounded pool used by the PostgreSQL cache, counter, lock, pub/sub, and job queue backends.
 *
 * Those backends need connections independent of a caller's transaction. Taking them from the main
 * pool lets requests that hold a transaction exhaust it while each waits for a second connection.
 * Every use of this pool is a single short statement that never waits on another connection.
 *
 * Connections always use `database.postgres`: transactional job enqueues and pub/sub publishes write
 * through the caller's main-pool connection, so workers and listeners on this pool must read the same
 * database. `database.postgres-infrastructure.maxConnections` sets the connection limit, which defaults
 * to [DEFAULT_MAX_CONNECTIONS]. The pool exists only when a PostgreSQL infrastructure backend is selected.
 */
class PostgresInfrastructurePoolProvider(private val application: BoscaApplication) : ObjectProvider<ConnectionPool> {

    override val type: KClass<ConnectionPool> = ConnectionPool::class

    override val exists: Boolean
        get() = BACKEND_SELECTORS.any { application.environment.config.propertyOrNull(it)?.getString() == "postgres" }

    /** Creates the pool; registration as a singleton provider ensures this runs once per process. */
    override suspend fun get(): ConnectionPool = ConnectionPool(ConnectionFactoryImpl(configuration(), NAME))

    internal fun configuration(): ConnectionConfig {
        val config = application.environment.config
        val ignored = CONNECTION_FIELDS.filter { config.propertyOrNull("database.$NAME.$it") != null }
        if (ignored.isNotEmpty()) {
            log.warn("Ignoring database.{} {}: the infrastructure pool must use the database.postgres database", NAME, ignored)
        }
        val maxConnections = config.propertyOrNull("database.$NAME.maxConnections")?.getString()?.toInt()
        return application.get("postgres").copy(maxConnections = maxConnections ?: DEFAULT_MAX_CONNECTIONS)
    }

    companion object {
        const val NAME = "postgres-infrastructure"
        const val DEFAULT_MAX_CONNECTIONS = 10
        private val CONNECTION_FIELDS = listOf("url", "user", "password")
        private val log = LoggerFactory.getLogger(PostgresInfrastructurePoolProvider::class.java)

        /** Settings whose `postgres` value selects a backend that stores state in the `infrastructure` schema. */
        val BACKEND_SELECTORS = listOf("cache.type", "counter.type", "pubsub.type", "distributedLock.type", "jobQueue.factory", "chat.storage")
    }
}
