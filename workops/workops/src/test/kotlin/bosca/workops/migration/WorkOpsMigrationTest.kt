package bosca.workops.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-unit assertions about the [WorkOpsMigration] declaration that do not
 * need a Postgres container. Catches accidental renames of the schema
 * constant or a Flyway file dropped onto disk without being registered in
 * `resources` (Flyway's custom `ResourceProvider` only
 * sees what the `Migration.resources` list explicitly enumerates).
 *
 * The Docker-dependent bookkeeping assertions live in
 * [WorkOpsMigrationSmokeTest].
 */
class WorkOpsMigrationTest {

    @Test
    fun `schema name is workops`() {
        assertEquals("workops", WorkOpsMigration().schema)
    }

    @Test
    fun `V1 initial migration is registered`() {
        val resources = WorkOpsMigration().resources
        assertTrue(
            resources.contains("V1__workops_initial.sql"),
            "V1 must be registered in WorkOpsMigration.resources; got $resources",
        )
    }

    @Test
    fun `every registered migration sits on the runtime classpath`() {
        // Placing a SQL file on disk is not sufficient — Flyway only loads
        // what `resources` enumerates. Invert
        // that check: every entry in `resources` must round-trip to a real
        // classpath resource. Catches typos in the registration list.
        val classLoader = WorkOpsMigration::class.java.classLoader
        for (resource in WorkOpsMigration().resources) {
            val stream = classLoader.getResourceAsStream("db/migrations/$resource")
                ?: error("Registered migration $resource is missing from db/migrations/ on the classpath")
            stream.close()
        }
    }
}
