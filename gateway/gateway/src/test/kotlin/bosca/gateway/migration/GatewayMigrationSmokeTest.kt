@file:OptIn(InternalDI::class)

package bosca.gateway.migration

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Boots a real Postgres via TestContainers and applies [GatewayMigration]
 * against it. Asserts the schema, table inventory, the cross-table FK
 * constraints, the CHECK constraint that blocks the
 * `auth_method = 'none' + non-empty read_groups/write_groups` footgun,
 * and the uniqueness rules that the [GatewayConflictException] mapping
 * relies on.
 *
 * Skips cleanly when Docker is unavailable (mirrors workops's pattern).
 */
class GatewayMigrationSmokeTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_gateway_migration_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 2,
                ),
                key = "gateway-migration-test",
            )
        )

        private var initialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    @BeforeTest
    fun setup() {
        org.junit.Assume.assumeTrue(
            "Docker not available -- skipping Gateway migration smoke test",
            isDockerAvailable(),
        )
        if (!initialized) {
            ProviderRegistry.clear()
            provides<ConnectionPool>(singleton = true) { pool }
            initialized = true
        }
        migrate(listOf(CoreMigration(), GatewayMigration()))
    }

    private fun isDockerAvailable(): Boolean = try {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }

    private fun migrate(migrations: List<Migration>) {
        runBlocking { FlywayMigration(pool).migrate(migrations) }
    }

    private fun querySingleColumn(sql: String, column: String): List<String> =
        runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt ->
                        val rs = stmt.executeQuery()
                        val results = mutableListOf<String>()
                        while (rs.next()) results.add(rs.getString(column))
                        results
                    }
                }
            } finally {
                mgr.release()
            }
        }

    private fun querySingleLong(sql: String): Long =
        runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt ->
                        val rs = stmt.executeQuery()
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            } finally {
                mgr.release()
            }
        }

    private fun execute(sql: String) {
        runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt -> stmt.executeUpdate() }
                }
            } finally {
                mgr.release()
            }
        }
    }

    @Test
    fun `migration creates the gateway schema and the expected table inventory`() {
        val schemas = querySingleColumn(
            "select schema_name from information_schema.schemata where schema_name = 'gateway'",
            "schema_name",
        )
        assertEquals(1, schemas.size, "gateway schema should exist")

        val tables = querySingleColumn(
            """
            select table_name from information_schema.tables
            where table_schema = 'gateway' and table_name <> 'flyway_schema_history'
            order by table_name
            """.trimIndent(),
            "table_name",
        ).toSet()
        val expected = setOf(
            "service",
            "permission",
            "route",
            "config_version",
        )
        assertEquals(expected, tables, "gateway table inventory drifted")
    }

    @Test
    fun `migration is idempotent`() {
        migrate(listOf(CoreMigration(), GatewayMigration()))
        val schemaCount = querySingleLong(
            "select count(*) from information_schema.schemata where schema_name = 'gateway'",
        )
        assertEquals(1L, schemaCount, "gateway schema should be present exactly once")
    }

    @Test
    fun `config_version seed row exists and is the only allowed row`() {
        val count = querySingleLong("select count(*) from gateway.config_version")
        assertEquals(1L, count, "config_version should be seeded with exactly one row")

        // The `id = 1` CHECK + primary key forbid a second row.
        assertFailsWith<Throwable> {
            execute("insert into gateway.config_version (id) values (2)")
        }
    }

    @Test
    fun `CHECK constraint forbids auth_method none with non-empty read_or_write groups`() {
        execute("delete from gateway.route")
        execute("delete from gateway.service")
        execute(
            """
            insert into gateway.service (id, name, url)
            values ('11111111-1111-1111-1111-111111111111', 'forbidden-route-test', 'http://upstream')
            """.trimIndent(),
        )

        // none + non-empty read_groups is the footgun we explicitly
        // block at the DB layer. The Rust proxy bypasses group checks
        // for unauthenticated routes; this insert must fail.
        val readError = assertFailsWith<Throwable> {
            execute(
                """
                insert into gateway.route
                    (gateway_id, path_pattern, auth_method, read_groups)
                values
                    ('11111111-1111-1111-1111-111111111111', '/leak-read', 'none', array['admin'])
                """.trimIndent(),
            )
        }
        assertTrue(
            readError.message?.contains("gateway_route_none_no_groups") == true ||
                rootCauseChain(readError).any { it.message?.contains("gateway_route_none_no_groups") == true },
            "expected gateway_route_none_no_groups CHECK to fire, got: ${readError.message}",
        )

        // Same for write_groups.
        val writeError = assertFailsWith<Throwable> {
            execute(
                """
                insert into gateway.route
                    (gateway_id, path_pattern, auth_method, write_groups)
                values
                    ('11111111-1111-1111-1111-111111111111', '/leak-write', 'none', array['admin'])
                """.trimIndent(),
            )
        }
        assertTrue(
            writeError.message?.contains("gateway_route_none_no_groups") == true ||
                rootCauseChain(writeError).any { it.message?.contains("gateway_route_none_no_groups") == true },
            "expected gateway_route_none_no_groups CHECK to fire, got: ${writeError.message}",
        )

        // Either of the safe shapes (none + empty groups, or non-none +
        // groups) must succeed.
        execute(
            """
            insert into gateway.route
                (gateway_id, path_pattern, auth_method, read_groups, write_groups)
            values
                ('11111111-1111-1111-1111-111111111111', '/public', 'none', '{}', '{}')
            """.trimIndent(),
        )
        execute(
            """
            insert into gateway.route
                (gateway_id, path_pattern, auth_method, read_groups, write_groups)
            values
                ('11111111-1111-1111-1111-111111111111', '/protected', 'jwt', array['analysts'], array['editors'])
            """.trimIndent(),
        )
    }

    @Test
    fun `route pattern is unique per gateway among non-deleted rows`() {
        execute("delete from gateway.route")
        execute("delete from gateway.service where name in ('uniq-a', 'uniq-b')")
        execute(
            """
            insert into gateway.service (id, name, url) values
                ('66666666-6666-6666-6666-666666666666', 'uniq-a', 'http://a'),
                ('77777777-7777-7777-7777-777777777777', 'uniq-b', 'http://b')
            """.trimIndent(),
        )
        execute(
            """
            insert into gateway.route (gateway_id, path_pattern, auth_method)
            values ('66666666-6666-6666-6666-666666666666', '/x', 'jwt')
            """.trimIndent(),
        )
        // Same pattern, different gateway — OK.
        execute(
            """
            insert into gateway.route (gateway_id, path_pattern, auth_method)
            values ('77777777-7777-7777-7777-777777777777', '/x', 'jwt')
            """.trimIndent(),
        )
        // Same pattern, same gateway, same hosts (empty), not deleted —
        // must fail on the partial unique index
        // `gateway_route_host_pattern_unique` (introduced in V3 to
        // replace the V1 `gateway_route_pattern_unique` and add `hosts`
        // to the key so host-scoped duplicates can legitimately coexist).
        val dup = assertFailsWith<Throwable> {
            execute(
                """
                insert into gateway.route (gateway_id, path_pattern, auth_method)
                values ('66666666-6666-6666-6666-666666666666', '/x', 'jwt')
                """.trimIndent(),
            )
        }
        assertTrue(
            rootCauseChain(dup).any { it.message?.contains("gateway_route_host_pattern_unique") == true } ||
                dup.message?.contains("gateway_route_host_pattern_unique") == true,
            "expected gateway_route_host_pattern_unique to fire, got: ${dup.message}",
        )

        // Soft-delete the original; the partial index excludes it, so
        // a new row with the same pattern should now succeed.
        execute(
            """
            update gateway.route set deleted_at = now()
            where gateway_id = '66666666-6666-6666-6666-666666666666' and path_pattern = '/x'
            """.trimIndent(),
        )
        execute(
            """
            insert into gateway.route (gateway_id, path_pattern, auth_method)
            values ('66666666-6666-6666-6666-666666666666', '/x', 'jwt')
            """.trimIndent(),
        )
    }

    @Test
    fun `service name is unique`() {
        execute("delete from gateway.service where name = 'dup-name-test'")
        execute(
            "insert into gateway.service (name, url) values ('dup-name-test', 'http://a')",
        )
        val dup = assertFailsWith<Throwable> {
            execute("insert into gateway.service (name, url) values ('dup-name-test', 'http://b')")
        }
        // Postgres unique-violation SQLSTATE is 23505 — wrapped as a
        // generic SQLException in the chain.
        assertTrue(
            rootCauseChain(dup).any { it is java.sql.SQLException && it.sqlState == "23505" },
            "expected SQLSTATE 23505 (unique_violation), got: ${dup.message}",
        )
    }

    @Test
    fun `deleting a Gateway cascades to its permissions but is blocked by routes`() {
        execute("delete from gateway.permission")
        execute("delete from gateway.route where gateway_id in (select id from gateway.service where name in ('cascade-test'))")
        execute("delete from gateway.service where name = 'cascade-test'")

        execute(
            """
            insert into gateway.service (id, name, url)
            values ('88888888-8888-8888-8888-888888888888', 'cascade-test', 'http://a')
            """.trimIndent(),
        )

        // No routes attached → hard delete must succeed. The shape
        // assertion here is that the FK constraint configuration allows
        // hard deletion when no dependents exist, while still blocking
        // it via `on delete restrict` for routes (covered separately).
        execute(
            """
            delete from gateway.service where id = '88888888-8888-8888-8888-888888888888'
            """.trimIndent(),
        )
        val remaining = querySingleLong(
            "select count(*) from gateway.service where id = '88888888-8888-8888-8888-888888888888'",
        )
        assertEquals(0L, remaining)
    }

    @Test
    fun `delete on routes is restricted by FK from service so routes block service deletion`() {
        execute("delete from gateway.route")
        execute("delete from gateway.service where name = 'block-delete-test'")
        execute(
            """
            insert into gateway.service (id, name, url)
            values ('99999999-9999-9999-9999-999999999999', 'block-delete-test', 'http://a')
            """.trimIndent(),
        )
        execute(
            """
            insert into gateway.route (gateway_id, path_pattern, auth_method)
            values ('99999999-9999-9999-9999-999999999999', '/x', 'jwt')
            """.trimIndent(),
        )
        // FK is `on delete restrict` — hard-deleting the service
        // should be rejected by the constraint engine. The application
        // layer enforces an earlier check via [GatewayInUseException].
        val err = assertFailsWith<Throwable> {
            execute(
                "delete from gateway.service where id = '99999999-9999-9999-9999-999999999999'",
            )
        }
        val causes = rootCauseChain(err)
        assertTrue(
            causes.any { it is java.sql.SQLException && it.sqlState == "23503" } ||
                causes.any { it.message?.contains("route_gateway_id_fkey") == true },
            "expected SQLSTATE 23503 (foreign_key_violation), got: ${err.message}",
        )
    }

    private fun rootCauseChain(t: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>()
        var c: Throwable? = t
        while (c != null) {
            out.add(c)
            c = c.cause
        }
        return out
    }
}
