package bosca.db.migrations

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards against the silent ship-blocker where a migration SQL file is added to db/migrations but never listed
 * in [CoreMigration.resources]. [FlywayMigration] runs an EXPLICIT resource list (it does not directory-scan),
 * so an unregistered file is physically present yet never executes at startup — and SQL end-to-end tests that
 * load migrations by filename pass anyway, hiding the gap. This test fails the moment the registry and the
 * directory diverge in either direction.
 */
class CoreMigrationRegistrationTest {

    private fun migrationFilesOnClasspath(): Set<String> {
        // Anchor on a known registered resource so this resolves the MAIN resources directory even though
        // the test classpath contributes its own db/migrations entries (the Flyway dependency test fixtures),
        // which would otherwise shadow the main directory in getResource("/db/migrations").
        val anchor = CoreMigration().resources.first()
        val url = javaClass.getResource("/db/migrations/$anchor") ?: error("/db/migrations/$anchor not found on the test classpath")
        val dir = File(url.toURI()).parentFile
        val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("""V\d+__.*\.sql""")) }
            ?: error("could not list ${dir.absolutePath}")
        return files.map { it.name }.toSet()
    }

    @Test
    fun `every migration file on disk is registered in CoreMigration resources`() {
        val onDisk = migrationFilesOnClasspath()
        val registered = CoreMigration().resources.toSet()
        val unregistered = onDisk - registered
        assertTrue(
            unregistered.isEmpty(),
            "migration files exist but are NOT registered in CoreMigration.resources — Flyway uses an explicit " +
                "resource list, so these will NEVER run at startup: $unregistered",
        )
    }

    @Test
    fun `every registered migration resource has a backing file`() {
        val onDisk = migrationFilesOnClasspath()
        val registered = CoreMigration().resources.toSet()
        val missing = registered - onDisk
        assertTrue(missing.isEmpty(), "CoreMigration.resources lists files that do not exist: $missing")
    }

    @Test
    fun `analytics cache hardening preserves the rolling deployment primary key`() {
        val sql = javaClass.getResource(
            "/db/migrations/V181__harden_analytics_query_result_caching.sql",
        )?.readText() ?: error("analytics cache hardening migration not found")

        assertFalse(
            Regex("""(?is)drop\s+constraint\s+analytics_query_cache_entries_pkey""").containsMatchIn(sql),
            "changing the existing cache primary key breaks writes from old pods during a rolling deployment",
        )
        assertFalse(
            Regex("""(?is)add\s+primary\s+key""").containsMatchIn(sql),
            "the additive migration must retain the existing (query_id, parameters_hash) primary key",
        )
        assertTrue("ADD COLUMN query_generation BIGINT NOT NULL DEFAULT 0" in sql)
        assertTrue("ADD COLUMN object_version UUID" in sql)
        assertTrue("ADD COLUMN superseded_object_version UUID" in sql)
        assertTrue("ADD COLUMN superseded_legacy_object BOOLEAN NOT NULL DEFAULT FALSE" in sql)
    }

    @Test
    fun `Bible variant availability allows at most one default and requires it to be enabled`() {
        val sql = javaClass.getResource(
            "/db/migrations/V193__bible_variant_availability.sql",
        )?.readText() ?: error("Bible variant availability migration not found")

        assertTrue(Regex("""(?i)add\s+column\s+enabled\s+boolean\s+not\s+null\s+default\s+true""").containsMatchIn(sql))
        assertTrue("CHECK (NOT default_variant OR enabled)" in sql)
        assertTrue("CREATE UNIQUE INDEX bibles_one_default_variant" in sql)
        assertTrue("WHERE default_variant" in sql)
    }

    @Test
    fun `domain language resolution tags are not constrained to Bosca locales`() {
        val sql = javaClass.getResource(
            "/db/migrations/V194__domain_language_resolution.sql",
        )?.readText() ?: error("domain language resolution migration not found")

        assertTrue(sql.contains("drop constraint if exists language_resolution_contexts_fallback_language_tag_fkey", ignoreCase = true))
        assertTrue(sql.contains("drop constraint if exists language_tag_mappings_resolved_language_tag_fkey", ignoreCase = true))
    }
}
