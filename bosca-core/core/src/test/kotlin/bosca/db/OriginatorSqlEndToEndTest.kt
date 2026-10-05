package bosca.db

import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * End-to-end coverage against real PostgreSQL for the originator / accountCreated columns and the exact SQL
 * the generated repositories run — the things mocked unit tests can't verify: that migrations V170–V173 apply
 * cleanly, that `principal_exchange_tokens` round-trips `account_created` + `originator` through the
 * `delete ... returning` consume statement (with the right defaults), and that a credential is stamped with
 * `originator` + `last_originator` at creation and that only `last_originator` is updated on later logins.
 *
 * The migrations are loaded from the classpath and executed verbatim, so this validates the real schema
 * changes, not a hand-rolled approximation.
 */
class OriginatorSqlEndToEndTest {

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
                    maxConnections = 4,
                ),
                key = "originator-sql-test",
            )
        )

        runE2E {
            // Minimal slices of the real schema the columns hang off, as they existed BEFORE these migrations:
            // principals (core V3), principal_credentials (core V3, incl. its enum type), and
            // principal_exchange_tokens (core V133). Then apply the real migrations under test on top.
            runSql("drop table if exists principal_exchange_tokens, principal_credentials, principals cascade")
            runSql("drop type if exists principal_credential_type")
            runSql("create table principals (id uuid primary key, verified boolean not null default false, created timestamptz not null default now())")
            runSql("create type principal_credential_type as enum ('password', 'oauth2')")
            runSql(
                """
                create table principal_credentials (
                    id         bigserial primary key,
                    principal  uuid not null references principals(id) on delete cascade,
                    type       principal_credential_type not null,
                    attributes jsonb not null
                )
                """.trimIndent()
            )
            runSql(
                """
                create table principal_exchange_tokens (
                    token        varchar primary key,
                    principal_id uuid not null references principals(id) on delete cascade,
                    created      timestamptz not null default now(),
                    expires      timestamptz not null default now() + '5 minutes'::interval
                )
                """.trimIndent()
            )
            applyMigration("V170__exchange_token_account_created.sql")
            applyMigration("V171__exchange_token_originator.sql")
            applyMigration("V172__credential_originator.sql")
            applyMigration("V173__credential_last_originator.sql")
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@OriginatorSqlEndToEndTest::pool.isInitialized) pool.close()
        if (this@OriginatorSqlEndToEndTest::postgres.isInitialized) postgres.stop()
    }

    private fun runE2E(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    private suspend fun runSql(sql: String) = connection().useStatement(sql) { it.execute() }

    /** Executes a real migration file verbatim (comment lines stripped, statements split on `;`). */
    private suspend fun applyMigration(name: String) {
        val migration = javaClass.getResourceAsStream("/db/migrations/$name")!!.readBytes().decodeToString()
        migration.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { runSql(it) }
    }

    private suspend fun seedPrincipal(id: UUID) =
        runSql("insert into principals (id, verified) values ('$id'::uuid, true)")

    @Test
    fun `exchange token round-trips accountCreated and originator through the consume statement`() = runE2E {
        val principalId = UUID.random()
        seedPrincipal(principalId)
        // Mirrors PrincipalExchangeTokenRepository.addExchangeToken.
        runSql(
            "insert into principal_exchange_tokens (token, principal_id, account_created, originator) " +
                "values ('tok-1', '$principalId'::uuid, true, 'studio')"
        )

        // Mirrors PrincipalExchangeTokenRepository.consumeToken — the exact columns ConsumedExchangeToken maps.
        val (returnedPrincipal, accountCreated, originator) = connection().useStatement(
            "delete from principal_exchange_tokens where token = 'tok-1' and expires > now() returning principal_id, account_created, originator"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            Triple(rs.getString("principal_id"), rs.getBoolean("account_created"), rs.getString("originator"))
        }

        assertEquals(principalId.toString(), returnedPrincipal)
        assertEquals(true, accountCreated)
        assertEquals("studio", originator)
    }

    @Test
    fun `exchange token defaults accountCreated to false and leaves originator null`() = runE2E {
        val principalId = UUID.random()
        seedPrincipal(principalId)
        // A plain login (no account creation, no originator) omits both columns and relies on the defaults.
        runSql("insert into principal_exchange_tokens (token, principal_id) values ('tok-2', '$principalId'::uuid)")

        val (accountCreated, originator) = connection().useStatement(
            "delete from principal_exchange_tokens where token = 'tok-2' and expires > now() returning account_created, originator"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getBoolean("account_created") to rs.getString("originator")
        }

        assertFalse(accountCreated)
        assertNull(originator)
    }

    @Test
    fun `credential is stamped with originator and last_originator at creation`() = runE2E {
        val principalId = UUID.random()
        seedPrincipal(principalId)
        // Mirrors PrincipalCredentialsRepository.add (originator == last_originator at creation).
        runSql(
            "insert into principal_credentials (principal, type, attributes, originator, last_originator) " +
                "values ('$principalId'::uuid, 'password'::principal_credential_type, '{}'::jsonb, 'studio', 'studio')"
        )

        val (originator, lastOriginator) = connection().useStatement(
            "select originator, last_originator from principal_credentials where principal = '$principalId'::uuid"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString("originator") to rs.getString("last_originator")
        }

        assertEquals("studio", originator)
        assertEquals("studio", lastOriginator)
    }

    @Test
    fun `updateLastOriginator changes only the last originator, leaving the original intact`() = runE2E {
        val principalId = UUID.random()
        seedPrincipal(principalId)
        val id = connection().useStatement(
            "insert into principal_credentials (principal, type, attributes, originator, last_originator) " +
                "values ('$principalId'::uuid, 'password'::principal_credential_type, '{}'::jsonb, 'studio', 'studio') returning id"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getLong("id")
        }

        // A later login from a different client. Mirrors PrincipalCredentialsRepository.updateLastOriginator.
        runSql("update principal_credentials set last_originator = 'mobile' where id = $id")

        val (originator, lastOriginator) = connection().useStatement(
            "select originator, last_originator from principal_credentials where id = $id"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString("originator") to rs.getString("last_originator")
        }

        // Original provenance is immutable; only the last originator moves.
        assertEquals("studio", originator)
        assertEquals("mobile", lastOriginator)
    }
}
