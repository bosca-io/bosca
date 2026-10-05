package bosca.devices.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.testcontainers.containers.wait.strategy.Wait

/** Verifies the real device migrations against PostgreSQL, including provider-scoped token identity. */
class DevicePushTokenSqlEndToEndTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    @BeforeTest
    fun setup() {
        postgres = SharedPostgreSQLContainer()
            .withExposedPorts(5432)
            .withEnv("POSTGRES_USER", "test")
            .withEnv("POSTGRES_PASSWORD", "test")
            .withEnv("POSTGRES_DB", "test")
            .withReuse(true)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        postgres.start()

        pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 2,
                ),
                key = "device-push-token-sql-test",
            ),
        )

        runE2E {
            runSql("drop schema if exists devices cascade")
            runSql("drop table if exists public.principals cascade")
            runSql("create table public.principals (id uuid primary key)")
            runSql("create schema devices")
            applyMigration("V1__devices.sql")
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@DevicePushTokenSqlEndToEndTest::pool.isInitialized) pool.close()
        if (this@DevicePushTokenSqlEndToEndTest::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V2 deduplicates legacy tokens and scopes uniqueness by provider`() = runE2E {
        val firstPrincipal = UUID.random()
        val secondPrincipal = UUID.random()
        val firstDevice = UUID.random()
        val secondDevice = UUID.random()
        runSql("insert into public.principals (id) values ('$firstPrincipal'), ('$secondPrincipal')")
        runSql(
            "insert into devices.devices (id, principal_id, platform, installation_id) values " +
                "('$firstDevice', '$firstPrincipal', 'ios', 'installation-1'), " +
                "('$secondDevice', '$secondPrincipal', 'ios', 'installation-2')",
        )
        runSql(
            "insert into devices.device_push_tokens (device_id, token, created) values " +
                "('$firstDevice', 'shared-token', now() - interval '1 minute'), " +
                "('$secondDevice', 'shared-token', now())",
        )

        applyMigration("V2__unique_push_tokens.sql")

        assertEquals(1, tokenCount("fcm", "shared-token"))
        assertEquals(secondDevice.toString(), tokenDevice("fcm", "shared-token"))
        runSql(
            "insert into devices.device_push_tokens (device_id, provider, token) " +
                "values ('$firstDevice', 'apns', 'shared-token')",
        )
        assertEquals(1, tokenCount("apns", "shared-token"))

        val duplicateRejected = try {
            runSql(
                "insert into devices.device_push_tokens (device_id, provider, token) " +
                    "values ('$firstDevice', 'fcm', 'shared-token')",
            )
            false
        } catch (error: Exception) {
            generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .any { it.sqlState == "23505" }
        }
        assertTrue(duplicateRejected, "the same provider must not own one token twice")
    }

    @Test
    fun `installation identity stays unique when principal association becomes nullable`() = runE2E {
        val firstPrincipal = UUID.random()
        val secondPrincipal = UUID.random()
        val firstDevice = UUID.random()
        val secondDevice = UUID.random()
        runSql("insert into public.principals (id) values ('$firstPrincipal'), ('$secondPrincipal')")
        runSql(
            "insert into devices.devices (id, principal_id, platform, installation_id) " +
                "values ('$firstDevice', '$firstPrincipal', 'android', 'installation-1')",
        )
        applyMigration("V3__nullable_device_principal.sql")
        runSql("update devices.devices set principal_id = null where id = '$firstDevice'")

        val duplicateRejected = try {
            runSql(
                "insert into devices.devices (id, principal_id, platform, installation_id) " +
                    "values ('$secondDevice', '$secondPrincipal', 'ios', 'installation-1')",
            )
            false
        } catch (error: Exception) {
            generateSequence(error as Throwable?) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .any { it.sqlState == "23505" }
        }

        assertTrue(duplicateRejected, "one installation ID must resolve to exactly one device")
    }

    private fun runE2E(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    private suspend fun applyMigration(name: String) {
        val sql = checkNotNull(javaClass.getResourceAsStream("/db/migrations/$name")) {
            "Missing migration $name"
        }.use { it.readBytes().decodeToString() }
        sql.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { runSql(it) }
    }

    private suspend fun runSql(sql: String) = connection().useStatement(sql) { it.execute() }

    private suspend fun tokenCount(provider: String, token: String): Int =
        connection().useStatement(
            "select count(*) from devices.device_push_tokens where provider = '$provider' and token = '$token'",
        ) { statement ->
            statement.executeQuery().use { results ->
                results.next()
                results.getInt(1)
            }
        }

    private suspend fun tokenDevice(provider: String, token: String): String =
        connection().useStatement(
            "select device_id from devices.device_push_tokens where provider = '$provider' and token = '$token'",
        ) { statement ->
            statement.executeQuery().use { results ->
                results.next()
                results.getString(1)
            }
        }
}
