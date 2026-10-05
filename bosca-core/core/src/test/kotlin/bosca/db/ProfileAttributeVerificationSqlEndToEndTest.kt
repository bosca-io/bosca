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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end coverage against real PostgreSQL for the attribute-level email-verification SQL that unit
 * tests can only mock: the real V161 (adds `verified` / `verification_token` / `verification_source`) and
 * V162 (backfills `verified` from proven login identifiers) migrations, plus the marking UPDATE and the
 * trust-gated lookup SELECT that `ProfileAttributeRepository` generates.
 *
 * Both migrations are loaded from the classpath and executed verbatim, so this validates the real SQL —
 * in particular the security claim that the V162 backfill promotes a genuine registration email but never
 * a squat attribute (a victim's address attached to an attacker's profile).
 */
class ProfileAttributeVerificationSqlEndToEndTest {

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
                key = "profile-attribute-verification-sql-test",
            )
        )

        runE2E {
            // Minimal slices of the real schema the migration/queries touch: principals + profiles
            // (core V3), principal_credentials (core V3), profile_attributes (core V21) — the verified
            // columns are then added by the real V161 migration below.
            runSql("drop table if exists principal_credentials, profile_attributes, profiles, principals cascade")
            // `verification_token` mirrors the real principals column (V21) so the V163 in-flight-token
            // migration can be exercised; `principal_credentials.type` mirrors the real enum column (V3/V47)
            // so the V162 credential-type filter is covered (varchar here — the test isn't validating the enum).
            runSql("create table principals (id uuid primary key, verified boolean not null default false, verification_token varchar, created timestamptz not null default now())")
            runSql("create table profiles (id uuid primary key, principal uuid references principals(id) on delete cascade)")
            runSql("create table principal_credentials (id bigserial primary key, principal uuid not null references principals(id) on delete cascade, type varchar not null default 'password', attributes jsonb)")
            runSql("create table profile_attributes (id uuid primary key default gen_random_uuid(), profile uuid not null references profiles(id) on delete cascade, type_id varchar not null, attributes jsonb)")
            applyMigration("V161__profile_attribute_verification.sql")
        }
    }

    @AfterTest
    fun teardown() = runBlocking {
        if (this@ProfileAttributeVerificationSqlEndToEndTest::pool.isInitialized) pool.close()
        if (this@ProfileAttributeVerificationSqlEndToEndTest::postgres.isInitialized) postgres.stop()
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

    /** Loads a migration from the classpath and executes it verbatim (statement by statement). */
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

    private suspend fun addCredential(principalId: UUID, identifier: String, type: String = "password") {
        runSql("insert into principal_credentials (principal, type, attributes) values ('$principalId'::uuid, '$type', '{\"identifier\":\"$identifier\"}'::jsonb)")
    }

    private suspend fun addPasswordCredential(principalId: UUID, identifier: String) = addCredential(principalId, identifier, "password")

    /** Seeds a principal (with [verified] / pending [verificationToken]) owning one profile carrying [email]. */
    private suspend fun seedProfileWithEmail(email: String, verified: Boolean = true, verificationToken: String? = null): Pair<UUID, UUID> {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val tokenLiteral = verificationToken?.let { "'$it'" } ?: "null"
        runSql("insert into principals (id, verified, verification_token) values ('$principalId'::uuid, $verified, $tokenLiteral)")
        runSql("insert into profiles (id, principal) values ('$profileId'::uuid, '$principalId'::uuid)")
        runSql("insert into profile_attributes (profile, type_id, attributes) values ('$profileId'::uuid, 'bosca.profiles.email', '{\"email\":\"$email\"}'::jsonb)")
        return principalId to profileId
    }

    private suspend fun isVerified(profileId: UUID, email: String): Boolean =
        connection().useStatement(
            "select verified from profile_attributes where profile = '$profileId'::uuid and lower(trim(attributes->>'email')) = lower(trim('$email'))"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getBoolean(1)
        }

    @Test
    fun `V162 backfill verifies an email that matches the principal's login identifier`() = runE2E {
        val (principalId, profileId) = seedProfileWithEmail("owner@example.com")
        addPasswordCredential(principalId, "owner@example.com")
        assertFalse(isVerified(profileId, "owner@example.com"), "fresh attribute starts unverified")

        applyMigration("V162__backfill_verified_email_attributes.sql")

        assertTrue(isVerified(profileId, "owner@example.com"), "the proven registration email is promoted")
    }

    @Test
    fun `V162 backfill never promotes a squat attribute on an attacker's profile`() = runE2E {
        // Attacker controls attacker@example.com (their login identifier) but has attached the victim's
        // address as a second email attribute on their own profile — the exact squat the verified column
        // exists to neutralize.
        val (attackerId, attackerProfile) = seedProfileWithEmail("attacker@example.com")
        addPasswordCredential(attackerId, "attacker@example.com")
        runSql("insert into profile_attributes (profile, type_id, attributes) values ('$attackerProfile'::uuid, 'bosca.profiles.email', '{\"email\":\"victim@example.com\"}'::jsonb)")

        applyMigration("V162__backfill_verified_email_attributes.sql")

        assertTrue(isVerified(attackerProfile, "attacker@example.com"), "the attacker's own proven email is promoted")
        assertFalse(isVerified(attackerProfile, "victim@example.com"), "the squatted victim address must stay unverified")
    }

    @Test
    fun `V162 backfill does not promote an unverified principal's email`() = runE2E {
        // Signed up with email == login identifier but never confirmed (principal stays unverified). Promoting
        // this would strand the account: a verified attribute on an unverified principal that can neither log
        // in nor be re-sent a link (requestVerification short-circuits on a verified attribute).
        val (principalId, profileId) = seedProfileWithEmail("pending@example.com", verified = false, verificationToken = null)
        addPasswordCredential(principalId, "pending@example.com")

        applyMigration("V162__backfill_verified_email_attributes.sql")

        assertFalse(isVerified(profileId, "pending@example.com"), "an account that never confirmed its email is not promoted")
    }

    @Test
    fun `V162 backfill does not promote via a non-password credential`() = runE2E {
        // A verified principal whose OAuth credential identifier happens to equal the email value must NOT be
        // promoted on that basis — only a password/scrypt identifier-match is proof of the address.
        val (principalId, profileId) = seedProfileWithEmail("oauthuser@example.com", verified = true, verificationToken = null)
        addCredential(principalId, "oauthuser@example.com", "oauth2")

        applyMigration("V162__backfill_verified_email_attributes.sql")

        assertFalse(isVerified(profileId, "oauthuser@example.com"), "only a password/scrypt identifier-match is proof")
    }

    @Test
    fun `V163 moves an in-flight email-verify token onto the attribute and clears the principal slot`() = runE2E {
        // An unverified principal with exactly ONE email attribute (no ambiguity) and a pending principal-level
        // token — a link already in the user's inbox.
        val (principalId, profileId) = seedProfileWithEmail("pending@example.com", verified = false, verificationToken = "inflight-tok")

        applyMigration("V163__migrate_inflight_email_verification_tokens.sql")

        // The token now lives on the email attribute, so the existing link resolves through attribute redemption.
        assertEquals("inflight-tok", tokenOf(profileId, "pending@example.com"), "the in-flight token is moved onto the attribute")
        // ...and is cleared from the principal so it can never later double as a password-reset token.
        val principalToken = connection().useStatement(
            "select verification_token from principals where id = '$principalId'::uuid"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString(1)
        }
        assertEquals(null, principalToken, "the principal verification_token slot is cleared")
    }

    @Test
    fun `V163 stamps NOTHING for a multi-email principal, never a co-attached squat address`() = runE2E {
        // Unverified principal whose verify link was delivered to their sign-up email, who has ALSO attached a
        // victim's address as a second email attribute. verifyByToken redeems set-based, so stamping the token
        // on more than one row would let a single click verify an address the user never controlled. With two
        // email attributes the migration cannot tell which one the link reached, so it stamps NEITHER — the
        // ambiguous case is left to a fresh resend that mails a new link to the address actually being proven.
        // This makes the squat unreachable by construction (at most one row is ever stamped, and here zero are).
        val (principalId, signupProfile) = seedProfileWithEmail("me@example.com", verified = false, verificationToken = "inflight-tok")
        addPasswordCredential(principalId, "me@example.com")
        val squatProfile = UUID.random()
        runSql("insert into profiles (id, principal) values ('$squatProfile'::uuid, '$principalId'::uuid)")
        runSql("insert into profile_attributes (profile, type_id, attributes) values ('$squatProfile'::uuid, 'bosca.profiles.email', '{\"email\":\"victim@example.com\"}'::jsonb)")

        applyMigration("V163__migrate_inflight_email_verification_tokens.sql")

        assertEquals(null, tokenOf(squatProfile, "victim@example.com"), "the squatted address must NOT receive the token — one click cannot falsely verify it")
        assertEquals(null, tokenOf(signupProfile, "me@example.com"), "the ambiguous multi-email principal gets no stamp; the link is re-sent fresh to the proven address")
        // The principal slot is still cleared (statement 2) so a stale token can never later serve as a reset token.
        val principalToken = connection().useStatement(
            "select verification_token from principals where id = '$principalId'::uuid"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString(1)
        }
        assertEquals(null, principalToken, "the principal verification_token slot is cleared even when no attribute is stamped")
    }

    @Test
    fun `the unique token index rejects copying one token onto a second attribute`() = runE2E {
        // The structural invariant verifyByToken's set-based redemption depends on: a token lives on at most
        // ONE row. The UNIQUE partial index (V161) enforces it — any write that would copy a token onto a
        // second attribute fails LOUDLY here instead of silently letting one click verify both addresses.
        val (_, profileA) = seedProfileWithEmail("a@example.com")
        val (_, profileB) = seedProfileWithEmail("b@example.com")
        runSql("update profile_attributes set verification_token = 'shared-tok' where profile = '$profileA'::uuid")
        assertFailsWith<java.sql.SQLException> {
            runSql("update profile_attributes set verification_token = 'shared-tok' where profile = '$profileB'::uuid")
        }
    }

    @Test
    fun `V164 enforces at most one password credential per principal`() = runE2E {
        applyMigration("V164__single_password_credential.sql")
        val (principalId, _) = seedProfileWithEmail("u@example.com")
        addPasswordCredential(principalId, "u@example.com")       // first password — allowed
        addCredential(principalId, "google-sub", "oauth2")        // a different credential type — coexists
        assertFailsWith<java.sql.SQLException> {
            addPasswordCredential(principalId, "alt@example.com") // second password — rejected by the index
        }
    }

    @Test
    fun `trust-gated lookup matches only verified email attributes (case-insensitive)`() = runE2E {
        val (_, verifiedProfile) = seedProfileWithEmail("Proven@Example.com")
        val (_, squatProfile) = seedProfileWithEmail("proven@example.com")
        // Only the first profile's attribute is proven (mirrors markEmailVerified scoped to one profile).
        runSql("update profile_attributes set verified = true, verification_source = 'email' where profile = '$verifiedProfile'::uuid")

        // Mirrors ProfileAttributeRepository.getVerifiedByValue (verified = true gate, lower(trim(...)) match).
        val matched = connection().useStatement(
            "select profile from profile_attributes where type_id = 'bosca.profiles.email' and verified = true and lower(trim(attributes->>'email')) = lower(trim('PROVEN@EXAMPLE.COM'))"
        ) { stmt ->
            val rs = stmt.executeQuery()
            buildList { while (rs.next()) add(UUID.parse(rs.getString("profile"))) }
        }

        assertEquals(listOf(verifiedProfile), matched, "only the verified profile resolves; the squat is ignored")
        assertFalse(squatProfile in matched)
    }

    private suspend fun tokenOf(profileId: UUID, email: String): String? =
        connection().useStatement(
            "select verification_token from profile_attributes where profile = '$profileId'::uuid and lower(trim(attributes->>'email')) = lower(trim('$email'))"
        ) { stmt ->
            val rs = stmt.executeQuery()
            rs.next()
            rs.getString(1)
        }

    @Test
    fun `verification token round-trip marks exactly the stamped attribute verified and clears the token`() = runE2E {
        val (_, profileId) = seedProfileWithEmail("owner@example.com")

        // setEmailVerificationToken: stamp a one-time token on the unverified attribute.
        runSql("update profile_attributes set verification_token = 'tok-123' where type_id = 'bosca.profiles.email' and profile = '$profileId'::uuid and verified = false and lower(trim(attributes->>'email')) = lower(trim('owner@example.com'))")
        assertEquals("tok-123", tokenOf(profileId, "owner@example.com"), "token is stamped")
        assertFalse(isVerified(profileId, "owner@example.com"), "still unverified until redeemed")

        // verifyEmailByToken: redeem the token → verified=true, source set, token cleared.
        runSql("update profile_attributes set verified = true, verification_source = 'email', verification_token = null where type_id = 'bosca.profiles.email' and verification_token = 'tok-123'")

        assertTrue(isVerified(profileId, "owner@example.com"), "the stamped attribute is now verified")
        assertEquals(null, tokenOf(profileId, "owner@example.com"), "the single-use token is cleared")
    }

    @Test
    fun `verifyEmailByToken redeems only the attribute carrying the token`() = runE2E {
        val (_, stamped) = seedProfileWithEmail("stamped@example.com")
        val (_, other) = seedProfileWithEmail("other@example.com")
        runSql("update profile_attributes set verification_token = 'only-this' where profile = '$stamped'::uuid")

        // A redemption for a different token touches nothing; the right token verifies exactly its attribute.
        runSql("update profile_attributes set verified = true, verification_source = 'email', verification_token = null where type_id = 'bosca.profiles.email' and verification_token = 'wrong-token'")
        assertFalse(isVerified(stamped, "stamped@example.com"), "wrong token verifies nothing")

        runSql("update profile_attributes set verified = true, verification_source = 'email', verification_token = null where type_id = 'bosca.profiles.email' and verification_token = 'only-this'")
        assertTrue(isVerified(stamped, "stamped@example.com"), "the carrying attribute is verified")
        assertFalse(isVerified(other, "other@example.com"), "an unrelated attribute is untouched")
    }

    @Test
    fun `the generic verified-value query binds the JSON key as a parameter at runtime`() = runE2E {
        // The generalization hinges on `attributes ->> ?` — the JSON value key bound as a parameter (so a
        // future "bosca.profiles.phone" + "phone" reuses the same query). KSP compiles it; this proves
        // Postgres actually binds and resolves it. Mirrors the generated getVerifiedByValue exactly.
        val (_, profileId) = seedProfileWithEmail("Param@Example.com")
        runSql("update profile_attributes set verified = true where profile = '$profileId'::uuid")

        val matched = connection().useStatement(
            "select profile from profile_attributes where type_id = ? and verified = true and lower(trim(attributes ->> ?)) = lower(trim(?))"
        ) { stmt ->
            stmt.setString(1, "bosca.profiles.email")
            stmt.setString(2, "email")              // ← the JSON key, bound as a parameter, not a literal
            stmt.setString(3, "PARAM@EXAMPLE.COM")
            val rs = stmt.executeQuery()
            buildList { while (rs.next()) add(UUID.parse(rs.getString("profile"))) }
        }
        assertEquals(listOf(profileId), matched, "attributes ->> ? resolves the value by a parameterized key")
    }
}
