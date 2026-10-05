package bosca.db

import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.testcontainers.containers.wait.strategy.Wait

/** PostgreSQL coverage for principal login history, refresh-token linkage, and revocation SQL. */
class PrincipalLoginSqlEndToEndTest {
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
                ConnectionConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 4),
                key = "principal-login-sql-test",
            ),
        )
        runE2E {
            resetSchema()
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@PrincipalLoginSqlEndToEndTest::pool.isInitialized) pool.close()
        if (this@PrincipalLoginSqlEndToEndTest::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `history is newest first pageable and deleted with its principal`() = runE2E {
        val principalId = UUID.random()
        runSql("insert into principals (id) values ('$principalId'::uuid)")
        runSql(
            "insert into principal_logins (principal_id, method, created) values " +
                "('$principalId'::uuid, 'password', now() - interval '2 minutes'), " +
                "('$principalId'::uuid, 'refresh_token', now() - interval '1 minute'), " +
                "('$principalId'::uuid, 'third_party', now())",
        )

        val page = connection().useStatement(
            "select * from principal_logins where principal_id = '$principalId'::uuid " +
                "order by created desc, id desc offset 1 limit 2",
        ) { statement ->
            val result = statement.executeQuery()
            buildList {
                while (result.next()) add(result.getString("method"))
            }
        }
        assertEquals(listOf("refresh_token", "password"), page)

        runSql("delete from principals where id = '$principalId'::uuid")
        val remaining = connection().useStatement("select count(*) from principal_logins") { statement ->
            val result = statement.executeQuery()
            result.next()
            result.getLong(1)
        }
        assertEquals(0, remaining)
    }

    @Test
    fun `refresh tokens retain a login id and targeted revocations expire independently`() = runE2E {
        val principalId = UUID.random()
        runSql("insert into principals (id) values ('$principalId'::uuid)")
        val loginId = connection().useStatement(
            "insert into principal_logins (principal_id, method) values ('$principalId'::uuid, 'password') returning id",
        ) { statement ->
            val result = statement.executeQuery()
            result.next()
            result.getLong("id")
        }
        runSql(
            "insert into principal_refresh_tokens (token, principal_id, login_id) " +
                "values ('tracked', '$principalId'::uuid, $loginId), ('legacy', '$principalId'::uuid, null)",
        )

        val refreshLoginIds = connection().useStatement(
            "select token, login_id from principal_refresh_tokens order by token",
        ) { statement ->
            val result = statement.executeQuery()
            buildMap {
                while (result.next()) put(result.getString("token"), result.getLong("login_id").takeUnless { result.wasNull() })
            }
        }
        assertEquals(mapOf("legacy" to null, "tracked" to loginId), refreshLoginIds)

        runSql("update principal_logins set revoked_at = now() where id = $loginId")
        runSql(
            "insert into principal_login_revocations (login_id, principal_id, expires_at) " +
                "values ($loginId, '$principalId'::uuid, now() + interval '30 minutes')",
        )
        runSql("update principals set has_login_revocations = true where id = '$principalId'::uuid")

        val liveRevocation = connection().useStatement(
            "select exists(select 1 from principal_login_revocations where login_id = $loginId and expires_at > now())",
        ) { statement ->
            val result = statement.executeQuery()
            result.next()
            result.getBoolean(1)
        }
        assertEquals(true, liveRevocation)

        runSql("update principal_login_revocations set expires_at = now() - interval '1 second' where login_id = $loginId")
        runSql(
            """
            with expired as (
                delete from principal_login_revocations
                where expires_at <= now()
                returning principal_id
            ), affected as (
                select distinct principal_id
                from expired
            )
            update principals p
            set has_login_revocations = false
            from affected a
            where p.id = a.principal_id
              and p.has_login_revocations
              and not exists (
                  select 1
                  from principal_login_revocations r
                  where r.principal_id = p.id
                    and r.expires_at > now()
              )
            """.trimIndent(),
        )
        val hasRevocations = connection().useStatement(
            "select has_login_revocations from principals where id = '$principalId'::uuid",
        ) { statement ->
            val result = statement.executeQuery()
            result.next()
            result.getBoolean(1)
        }
        assertEquals(false, hasRevocations)
    }

    @Test
    fun `principal revocation migration backfills existing rows`() = runE2E {
        resetSchema(includePrincipalRevocationMigration = false)
        val principalId = UUID.random()
        runSql("insert into principals (id) values ('$principalId'::uuid)")
        val loginId = connection().useStatement(
            "insert into principal_logins (principal_id, method) values ('$principalId'::uuid, 'password') returning id",
        ) { statement ->
            val result = statement.executeQuery()
            result.next()
            result.getLong("id")
        }
        runSql(
            "insert into principal_login_revocations (login_id, expires_at) " +
                "values ($loginId, now() + interval '30 minutes')",
        )

        applyMigration("V184__principal_login_revocation_principals.sql")

        val migratedPrincipalId = connection().useStatement(
            "select principal_id from principal_login_revocations where login_id = $loginId",
        ) { statement ->
            val result = statement.executeQuery()
            result.next()
            result.getString("principal_id")
        }
        assertEquals(principalId.toString(), migratedPrincipalId)
    }

    private fun runE2E(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    private suspend fun runSql(sql: String) = connection().useStatement(sql) { it.execute() }

    private suspend fun resetSchema(includePrincipalRevocationMigration: Boolean = true) {
        runSql("drop table if exists principal_login_revocations, principal_refresh_tokens, principal_logins, principals cascade")
        runSql("create table principals (id uuid primary key)")
        applyMigration("V24__refresh_token.sql")
        applyMigration("V182__principal_logins.sql")
        applyMigration("V183__principal_login_revocations.sql")
        if (includePrincipalRevocationMigration) {
            applyMigration("V184__principal_login_revocation_principals.sql")
        }
    }

    private suspend fun applyMigration(name: String) {
        val migration = javaClass.getResourceAsStream("/db/migrations/$name")!!.readBytes().decodeToString()
        migration.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { runSql(it) }
    }
}
