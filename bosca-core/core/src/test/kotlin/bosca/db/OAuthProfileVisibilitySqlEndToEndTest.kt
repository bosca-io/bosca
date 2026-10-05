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

/**
 * End-to-end coverage against real PostgreSQL for the V169 backfill that re-privatizes profiles and profile
 * attributes left 'public' (notably by the old OAuth/third-party signup path, which created profiles as
 * 'public'). The full-chain migration test runs V169 against an empty database, which only proves the SQL
 * parses — this validates the semantics on seeded data:
 *
 *   * every 'public' profile flips to 'user' and gets a fresh `modified` timestamp, regardless of profile
 *     type or whether it has a principal
 *   * every 'public' profile attribute flips to 'user'
 *   * non-public profiles are untouched (asserted via an unmoved `modified` timestamp, not just visibility),
 *     and non-public attributes keep their visibility
 *   * re-running the migration is a no-op
 *
 * The V169 migration is loaded from the classpath and executed verbatim, so this validates the real file.
 */
class OAuthProfileVisibilitySqlEndToEndTest {

    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var pool: ConnectionPool

    /** A sentinel `modified` value far in the past; an untouched row must still carry it after the backfill. */
    private val seededModified = "2020-01-01T00:00:00Z"

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
                key = "oauth-profile-visibility-sql-test",
            )
        )

        runE2E {
            // Minimal slices of the real schema V169 touches (profiles/attributes: core V3,
            // profile type: core V66, modified: core V74).
            runSql("drop table if exists principal_credentials, profile_attributes, profiles, principals cascade")
            runSql("drop type if exists profile_visibility, profile_type cascade")
            runSql("create type profile_visibility as enum ('system', 'user', 'friends', 'friends_of_friends', 'public')")
            runSql("create type profile_type as enum ('generic', 'organization')")
            runSql("create table principals (id uuid primary key)")
            runSql(
                """
                create table profiles (
                    id         uuid               primary key,
                    principal  uuid               references principals (id) on delete cascade,
                    type       profile_type       not null default 'generic'::profile_type,
                    visibility profile_visibility not null default 'system'::profile_visibility,
                    modified   timestamptz        not null
                )
                """.trimIndent()
            )
            runSql(
                """
                create table profile_attributes (
                    id         uuid               primary key,
                    profile    uuid               not null references profiles (id) on delete cascade,
                    visibility profile_visibility not null default 'system'::profile_visibility
                )
                """.trimIndent()
            )
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@OAuthProfileVisibilitySqlEndToEndTest::pool.isInitialized) pool.close()
        if (this@OAuthProfileVisibilitySqlEndToEndTest::postgres.isInitialized) postgres.stop()
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

    private suspend fun seedPrincipal(): UUID {
        val id = UUID.random()
        runSql("insert into principals (id) values ('$id'::uuid)")
        return id
    }

    private suspend fun seedProfile(principal: UUID?, type: String, visibility: String): UUID {
        val id = UUID.random()
        val principalValue = principal?.let { "'$it'::uuid" } ?: "null"
        runSql(
            "insert into profiles (id, principal, type, visibility, modified) " +
                "values ('$id'::uuid, $principalValue, '$type'::profile_type, '$visibility'::profile_visibility, '$seededModified'::timestamptz)"
        )
        return id
    }

    private suspend fun seedAttribute(profile: UUID, visibility: String): UUID {
        val id = UUID.random()
        runSql(
            "insert into profile_attributes (id, profile, visibility) " +
                "values ('$id'::uuid, '$profile'::uuid, '$visibility'::profile_visibility)"
        )
        return id
    }

    /** Executes the real V169 migration verbatim. */
    private suspend fun applyV169() {
        val migration = javaClass.getResourceAsStream("/db/migrations/V169__private_oauth_signup_profiles.sql")!!
            .readBytes().decodeToString()
        migration.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { runSql(it) }
    }

    private suspend fun visibilityOf(table: String, id: UUID): String =
        connection().useStatement("select visibility::varchar from $table where id = '$id'::uuid") { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString(1)
        }

    private suspend fun isUntouched(profileId: UUID): Boolean =
        connection().useStatement("select modified = '$seededModified'::timestamptz from profiles where id = '$profileId'::uuid") { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getBoolean(1)
        }

    @Test
    fun `flips every public profile to user and bumps modified, regardless of type or principal`() = runE2E {
        val generic = seedProfile(seedPrincipal(), "generic", "public")
        val organization = seedProfile(seedPrincipal(), "organization", "public")
        val principalLess = seedProfile(null, "generic", "public")

        applyV169()

        for (flipped in listOf(generic, organization, principalLess)) {
            assertEquals("user", visibilityOf("profiles", flipped))
            assertEquals(false, isUntouched(flipped), "a flipped profile must get a fresh modified timestamp")
        }
    }

    @Test
    fun `leaves non-public profiles untouched`() = runE2E {
        val expected = mapOf(
            seedProfile(seedPrincipal(), "generic", "system") to "system",
            seedProfile(seedPrincipal(), "generic", "user") to "user",
            seedProfile(seedPrincipal(), "generic", "friends") to "friends",
            seedProfile(seedPrincipal(), "organization", "friends_of_friends") to "friends_of_friends",
        )

        applyV169()

        for ((profile, visibility) in expected) {
            assertEquals(visibility, visibilityOf("profiles", profile))
            assertEquals(true, isUntouched(profile), "a non-public profile must not even have modified bumped")
        }
    }

    @Test
    fun `flips public profile attributes to user and leaves non-public attributes untouched`() = runE2E {
        val profile = seedProfile(seedPrincipal(), "generic", "user")
        val publicAttribute = seedAttribute(profile, "public")
        val userAttribute = seedAttribute(profile, "user")
        val systemAttribute = seedAttribute(profile, "system")
        // Attribute visibility flips even when the owning profile was never public.
        assertEquals("user", visibilityOf("profiles", profile))

        applyV169()

        assertEquals("user", visibilityOf("profile_attributes", publicAttribute))
        assertEquals("user", visibilityOf("profile_attributes", userAttribute))
        assertEquals("system", visibilityOf("profile_attributes", systemAttribute))
    }

    @Test
    fun `re-running the backfill is a no-op`() = runE2E {
        val profile = seedProfile(seedPrincipal(), "generic", "public")
        val attribute = seedAttribute(profile, "public")

        applyV169()
        assertEquals("user", visibilityOf("profiles", profile))
        assertEquals("user", visibilityOf("profile_attributes", attribute))
        val modifiedAfterFirstRun = connection().useStatement(
            "select modified::varchar from profiles where id = '$profile'::uuid"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString(1)
        }

        applyV169()
        assertEquals("user", visibilityOf("profiles", profile))
        val modifiedAfterSecondRun = connection().useStatement(
            "select modified::varchar from profiles where id = '$profile'::uuid"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString(1)
        }
        assertEquals(modifiedAfterFirstRun, modifiedAfterSecondRun, "a second run must not rewrite already-flipped rows")
    }
}
