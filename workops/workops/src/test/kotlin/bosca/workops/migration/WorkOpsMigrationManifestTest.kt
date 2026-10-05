package bosca.workops.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the migration manifest: every .sql file must be listed (a missing entry silently never
 * runs — a known failure mode that has bitten twice) and versions must be gapless and ordered.
 */
class WorkOpsMigrationManifestTest {

    private val migration = WorkOpsMigration()

    @Test
    fun `lists every migration resource in version order`() {
        val versions = migration.resources.map { it.substringBefore("__").removePrefix("V").toInt() }
        assertEquals((1..versions.size).toList(), versions, "migration versions must be gapless and ordered")
        assertTrue(migration.resources.all { it.endsWith(".sql") })
    }

    @Test
    fun `every listed resource exists on the classpath`() {
        for (resource in migration.resources) {
            val path = "/db/migrations/$resource"
            assertTrue(
                javaClass.getResource(path) != null,
                "migration listed but missing from resources: $path",
            )
        }
    }

    @Test
    fun `every migration file on disk is listed in the manifest`() {
        // Anchor on a known own resource so we enumerate THIS module's migrations directory, not
        // another module's /db/migrations that happens to be first on the classpath.
        val anchor = javaClass.getResource("/db/migrations/V1__workops_initial.sql")
            ?: error("anchor migration missing from the classpath")
        val onDisk = java.io.File(anchor.toURI()).parentFile.list()
            ?.filter { it.endsWith(".sql") }
            ?.sortedBy { it.substringBefore("__").removePrefix("V").toInt() }
            ?: emptyList()
        assertEquals(
            onDisk, migration.resources,
            "a .sql file exists on disk but is not in the manifest — it will silently never run",
        )
    }
}
