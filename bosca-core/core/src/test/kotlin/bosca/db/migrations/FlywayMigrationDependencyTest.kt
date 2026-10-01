package bosca.db.migrations

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.use
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests for [FlywayMigration]'s cross-schema dependency resolution against a real
 * PostgreSQL instance.
 *
 * The platform has multiple composition roots (server, runner, analytics collector/processor)
 * that load overlapping sets of migration chains against a shared database. A [Migration.dependsOn]
 * entry may therefore name a schema that the current application never registers — that dependency
 * is satisfied when another application has already created the schema, and a chain whose
 * dependencies cannot be satisfied at all must be skipped (and retried on a later startup) rather
 * than failing startup.
 */
class FlywayMigrationDependencyTest {

    private lateinit var postgresContainer: SharedPostgreSQLContainer
    private lateinit var connectionPool: ConnectionPool

    private class TestMigration(
        override val schema: String,
        override val dependsOn: List<String> = emptyList(),
    ) : Migration {
        override val resources: List<String> = listOf("V1__flyway_dependency_test_marker.sql")
    }

    @BeforeTest
    fun setup() = runBlocking {
        postgresContainer = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        postgresContainer.start()

        val factory = ConnectionFactoryImpl(
            ConnectionConfig(
                url = postgresContainer.jdbcUrl,
                user = postgresContainer.username,
                password = postgresContainer.password,
                maxConnections = 10
            ),
            key = "test"
        )
        connectionPool = ConnectionPool(factory)

        // The container is reused across tests; start each test from a clean slate.
        for (schema in TEST_SCHEMAS) {
            execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (::connectionPool.isInitialized) connectionPool.close()
        if (::postgresContainer.isInitialized) postgresContainer.stop()
    }

    private suspend fun execute(sql: String) {
        connectionPool.connection().use { cm ->
            cm.useStatement(sql) { it.execute() }
        }
    }

    private suspend fun markerTableExists(schema: String): Boolean =
        connectionPool.connection().use { cm ->
            cm.useStatement("SELECT 1 FROM pg_tables WHERE schemaname = ? AND tablename = 'marker'") { stmt ->
                stmt.setString(1, schema)
                stmt.executeQuery().use { it.next() }
            }
        }

    private suspend fun schemaExists(schema: String): Boolean =
        connectionPool.connection().use { cm ->
            cm.useStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?") { stmt ->
                stmt.setString(1, schema)
                stmt.executeQuery().use { it.next() }
            }
        }

    @Test
    fun `migration depending on a registered schema runs once the dependency completes`() = runBlocking {
        FlywayMigration(connectionPool).migrate(
            listOf(
                TestMigration(schema = "fdt_child", dependsOn = listOf("fdt_parent")),
                TestMigration(schema = "fdt_parent"),
            )
        )

        assertTrue(markerTableExists("fdt_parent"), "dependency chain should have been applied")
        assertTrue(markerTableExists("fdt_child"), "dependent chain should have been applied after its dependency")
    }

    @Test
    fun `dependency on a schema managed by another application is satisfied when the schema exists`() = runBlocking {
        // Simulate a different application (sharing the database) having already migrated its schema.
        execute("CREATE SCHEMA fdt_external")

        FlywayMigration(connectionPool).migrate(
            listOf(TestMigration(schema = "fdt_consumer", dependsOn = listOf("fdt_external")))
        )

        assertTrue(
            markerTableExists("fdt_consumer"),
            "a dependency on an unregistered but existing schema should be treated as satisfied"
        )
    }

    @Test
    fun `migration with an unsatisfiable dependency is skipped without failing the remaining migrations`() = runBlocking {
        FlywayMigration(connectionPool).migrate(
            listOf(
                TestMigration(schema = "fdt_orphan", dependsOn = listOf("fdt_missing")),
                TestMigration(schema = "fdt_independent"),
            )
        )

        assertTrue(markerTableExists("fdt_independent"), "unrelated chains should still be applied")
        assertFalse(schemaExists("fdt_orphan"), "a chain with an unsatisfiable dependency should be skipped")
    }

    @Test
    fun `circular dependencies are skipped without failing startup`() = runBlocking {
        FlywayMigration(connectionPool).migrate(
            listOf(
                TestMigration(schema = "fdt_circular_a", dependsOn = listOf("fdt_circular_b")),
                TestMigration(schema = "fdt_circular_b", dependsOn = listOf("fdt_circular_a")),
            )
        )

        assertFalse(schemaExists("fdt_circular_a"), "circular chains should be skipped")
        assertFalse(schemaExists("fdt_circular_b"), "circular chains should be skipped")
    }

    companion object {

        private val TEST_SCHEMAS = listOf(
            "fdt_parent",
            "fdt_child",
            "fdt_external",
            "fdt_consumer",
            "fdt_orphan",
            "fdt_independent",
            "fdt_circular_a",
            "fdt_circular_b",
        )
    }
}
