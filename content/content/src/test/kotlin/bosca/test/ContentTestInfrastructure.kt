package bosca.test

import bosca.cache.CacheManager
import bosca.cache.nats.NatsCacheManager
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionManager
import bosca.db.ConnectionPool
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.use
import bosca.nats.NatsConnectionPool
import bosca.test.resources.SharedNatsContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.lifecycle.Startables
import bosca.test.resources.SharedPostgreSQLContainer

/**
 * Class-scoped Testcontainers infrastructure for database-backed content tests.
 *
 * [reset] migrates once per class, then copies the pristine database between test methods. Copies
 * preserve the schema, seed rows, and sequence values while cache entries and client pools are reset.
 */
internal class ContentTestInfrastructure(
    private val includePostgres: Boolean = true,
) {

    private lateinit var natsContainer: SharedNatsContainer
    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private var databaseTemplate: SharedPostgreSQLContainer? = null
    private lateinit var natsPool: NatsConnectionPool

    lateinit var connectionPool: ConnectionPool
        private set

    lateinit var cacheManager: CacheManager
        private set

    suspend fun start() {
        natsContainer = SharedNatsContainer()
            .withExposedPorts(4222)
            .withCommand("-js")
            .waitingFor(Wait.forListeningPort())

        if (includePostgres) {
            postgresContainer = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
                withUsername("test")
                withPassword("test")
                withDatabaseName("test")
            }
            Startables.deepStart(natsContainer, postgresContainer).join()
        } else {
            natsContainer.start()
        }
    }

    suspend fun reset() {
        if (::cacheManager.isInitialized) {
            cacheManager.clearAll()
        }
        if (::natsPool.isInitialized) {
            natsPool.close()
        }
        val natsUrl = "nats://${natsContainer.host}:${natsContainer.getMappedPort(4222)}"
        natsPool = natsContainer.newConnectionPool(1)
        cacheManager = NatsCacheManager(natsPool)

        if (includePostgres) {
            if (::connectionPool.isInitialized) {
                connectionPool.close()
            }
            val template = databaseTemplate
            if (template == null) {
                connectionPool = createConnectionPool()
                resetTestDatabase(connectionPool)
                connectionPool.close()
                databaseTemplate = postgresContainer.copy()
            } else {
                postgresContainer.stop()
                postgresContainer = template.copy()
            }
            connectionPool = createConnectionPool()
        }
    }

    suspend fun stop() {
        var failure: Throwable? = null

        suspend fun cleanup(block: suspend () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val firstFailure = failure
                if (firstFailure == null) {
                    failure = error
                } else {
                    firstFailure.addSuppressed(error)
                }
            }
        }

        cleanup {
            if (::connectionPool.isInitialized) connectionPool.close()
        }
        cleanup {
            if (::natsPool.isInitialized) natsPool.close()
        }
        cleanup {
            if (::natsContainer.isInitialized) natsContainer.stop()
        }
        cleanup {
            if (::postgresContainer.isInitialized) postgresContainer.stop()
        }
        cleanup {
            databaseTemplate?.stop()
            databaseTemplate = null
        }

        failure?.let { throw it }
    }

    private fun createConnectionPool() = ConnectionPool(
        ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
            ),
            key = "test",
        ),
    )
}

private suspend fun resetTestDatabase(connectionPool: ConnectionPool) {
    ConnectionManager(connectionPool).use { connection ->
        connection.useStatement("drop schema public cascade") { statement ->
            statement.execute()
        }
        connection.useStatement("create schema public") { statement ->
            statement.execute()
        }
    }
    FlywayMigration(connectionPool).migrate(listOf(CoreMigration()))
}
