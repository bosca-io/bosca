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
import kotlin.test.assertTrue

/**
 * End-to-end coverage against real PostgreSQL for the account-identity SQL that unit tests can only
 * mock: the `principal_emails` backstop (the actual V160 migration — table, PK, and dedup-safe
 * backfill) and the duplicate-account finder query.
 *
 * The V160 migration is loaded from the classpath and executed verbatim, so this validates the real
 * migration — including the claim that its `DISTINCT ON` backfill cannot violate its own primary key
 * even when run against a database that already contains duplicate verified accounts.
 */
class VerifiedEmailSqlEndToEndTest {

    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    // Mirrors PrincipalRepository.findDuplicateVerifiedEmailAccounts (the finder) — including the
    // pa.verified/pa2.verified gates so an unverified squat attribute can't manufacture a false duplicate.
    private val finderSql = """
        select distinct lower(trim(pa.attributes->>'email')) as email, p.id as principal_id
        from profile_attributes pa
        join profiles pr on pr.id = pa.profile
        join principals p on p.id = pr.principal
        where pa.type_id = 'bosca.profiles.email'
          and pa.verified = true
          and p.verified = true
          and lower(trim(pa.attributes->>'email')) in (
            select lower(trim(pa2.attributes->>'email'))
            from profile_attributes pa2
            join profiles pr2 on pr2.id = pa2.profile
            join principals p2 on p2.id = pr2.principal
            where pa2.type_id = 'bosca.profiles.email' and pa2.verified = true and p2.verified = true
            group by lower(trim(pa2.attributes->>'email'))
            having count(distinct p2.id) > 1
          )
        order by email
    """.trimIndent()

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
                key = "verified-email-sql-test",
            )
        )

        runE2E {
            // Minimal slices of the real schema the finder/migration touch (principals: core V3,
            // profiles: core V3, profile_attributes: core V21).
            runSql("drop table if exists principal_emails, profile_attributes, profiles, principals cascade")
            runSql("create table principals (id uuid primary key, verified boolean not null default false, created timestamptz not null default now())")
            runSql("create table profiles (id uuid primary key, principal uuid references principals(id) on delete cascade)")
            runSql("create table profile_attributes (id uuid primary key default gen_random_uuid(), profile uuid not null references profiles(id) on delete cascade, type_id varchar not null, attributes jsonb, verified boolean not null default false)")
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@VerifiedEmailSqlEndToEndTest::pool.isInitialized) pool.close()
        if (this@VerifiedEmailSqlEndToEndTest::postgres.isInitialized) postgres.stop()
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

    /**
     * Seeds a principal owning [email] via one profile + email attribute. [verified] is the PRINCIPAL flag;
     * [attributeVerified] is the email ATTRIBUTE's proof flag (defaults to match the principal — a verified
     * account normally has a verified email — but can be set false to model an unverified squat attribute).
     */
    private suspend fun seedAccount(id: UUID, email: String, verified: Boolean, attributeVerified: Boolean = verified) {
        val profileId = UUID.random()
        runSql("insert into principals (id, verified) values ('$id'::uuid, $verified)")
        runSql("insert into profiles (id, principal) values ('$profileId'::uuid, '$id'::uuid)")
        runSql("insert into profile_attributes (profile, type_id, attributes, verified) values ('$profileId'::uuid, 'bosca.profiles.email', '{\"email\":\"$email\"}'::jsonb, $attributeVerified)")
    }

    /** Executes the real V160 migration verbatim (table + index + dedup-safe backfill). */
    private suspend fun applyV160() {
        val migration = javaClass.getResourceAsStream("/db/migrations/V160__principal_emails.sql")!!
            .readBytes().decodeToString()
        migration.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { runSql(it) }
    }

    private suspend fun countVerifiedEmails(email: String): Int =
        connection().useStatement("select count(*) from principal_emails where email = lower(trim('$email'))") { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getInt(1)
        }

    @Test
    fun `V160 backfill is dedup-safe and never violates its own primary key on dirty data`() = runE2E {
        // Two verified accounts already share an email (the exact dirty-data case the feature remediates),
        // plus a third unrelated verified account.
        val a = UUID.random()
        val b = UUID.random()
        val c = UUID.random()
        seedAccount(a, "dup@example.com", verified = true)
        seedAccount(b, "dup@example.com", verified = true)
        seedAccount(c, "solo@example.com", verified = true)

        // The migration must succeed (no PK violation) despite the pre-existing duplicate.
        applyV160()

        // Exactly one principal_emails row per email — the duplicate collapsed to a single owner.
        assertEquals(1, countVerifiedEmails("dup@example.com"))
        assertEquals(1, countVerifiedEmails("solo@example.com"))
    }

    @Test
    fun `principal_emails primary key rejects a second principal for the same email`() = runE2E {
        val a = UUID.random()
        val b = UUID.random()
        seedAccount(a, "owner@example.com", verified = true)
        seedAccount(b, "owner@example.com", verified = true)
        applyV160()

        // After backfill 'owner@example.com' is registered to one principal; a second claim must fail.
        var rejected = false
        try {
            runSql("insert into principal_emails (email, principal) values (lower(trim('owner@example.com')), '$b'::uuid)")
        } catch (e: Exception) {
            rejected = isUniqueViolation(e)
        }
        assertTrue(rejected, "a second principal claiming the same verified email must hit the PK")
    }

    @Test
    fun `backfill registers only verified accounts`() = runE2E {
        val verified = UUID.random()
        val unverified = UUID.random()
        seedAccount(unverified, "pending@example.com", verified = false)
        applyV160()
        assertEquals(0, countVerifiedEmails("pending@example.com"))

        // And a later verification of that email can claim it cleanly (the registry FK requires the
        // principal to exist).
        runSql("insert into principals (id, verified) values ('$verified'::uuid, true)")
        runSql("insert into principal_emails (email, principal) values (lower(trim('pending@example.com')), '$verified'::uuid)")
        assertEquals(1, countVerifiedEmails("pending@example.com"))
    }

    @Test
    fun `duplicate-account finder groups verified principals sharing an email and ignores singletons and unverified`() = runE2E {
        val a = UUID.random()
        val b = UUID.random()
        val c = UUID.random()
        val unverified = UUID.random()
        seedAccount(a, "dup@example.com", verified = true)
        seedAccount(b, "dup@example.com", verified = true)
        seedAccount(c, "solo@example.com", verified = true)        // singleton — not a duplicate
        seedAccount(unverified, "dup@example.com", verified = false) // unverified — must not count

        val rows = connection().useStatement(finderSql) { stmt ->
            val rs = stmt.executeQuery()
            buildList {
                while (rs.next()) add(rs.getString("email") to rs.getString("principal_id"))
            }
        }

        val byEmail = rows.groupBy({ it.first }, { it.second })
        assertEquals(setOf("dup@example.com"), byEmail.keys, "only the duplicated verified email is returned")
        assertEquals(setOf(a.toString(), b.toString()), byEmail.getValue("dup@example.com").toSet())
    }

    @Test
    fun `duplicate-account finder ignores an unverified squat attribute sharing a victim's verified email`() = runE2E {
        // Attacker is a verified principal who attached the victim's address as an UNVERIFIED email attribute.
        // The victim legitimately proved the same address. Only the victim has a VERIFIED attribute, so the
        // finder must NOT surface the pair — an unverified squat cannot manufacture a false merge candidate.
        val victim = UUID.random()
        val attacker = UUID.random()
        seedAccount(victim, "shared@example.com", verified = true)                               // proven attribute
        seedAccount(attacker, "shared@example.com", verified = true, attributeVerified = false)  // squat: unverified

        val rows = connection().useStatement(finderSql) { stmt ->
            val rs = stmt.executeQuery()
            buildList { while (rs.next()) add(rs.getString("email") to rs.getString("principal_id")) }
        }

        assertTrue(rows.isEmpty(), "only one principal has a VERIFIED attribute for the email — not a duplicate; the squat must be ignored")
    }

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is java.sql.SQLException && cause.sqlState == "23505") return true
            cause = cause.cause
        }
        return false
    }
}
