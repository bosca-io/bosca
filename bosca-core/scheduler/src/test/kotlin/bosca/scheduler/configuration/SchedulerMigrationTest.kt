package bosca.scheduler.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerMigrationTest {

    private val migration = SchedulerMigration()

    @Test
    fun `schema is scheduler`() {
        assertEquals("scheduler", migration.schema)
    }

    @Test
    fun `resources contains V1 migration`() {
        assertTrue(migration.resources.contains("V1__scheduler_tables.sql"))
    }

    @Test
    fun `resources contains all expected migrations`() {
        assertEquals(9, migration.resources.size)
        assertTrue(migration.resources.contains("V1__scheduler_tables.sql"))
        assertTrue(migration.resources.contains("V2__rename_executions_to_job_history.sql"))
        assertTrue(migration.resources.contains("V3__add_name_to_job_history.sql"))
        assertTrue(migration.resources.contains("V4__add_context_to_job_history.sql"))
        assertTrue(migration.resources.contains("V5__add_delayed_until_to_job_history.sql"))
        assertTrue(migration.resources.contains("V6__add_cancelled_status.sql"))
        assertTrue(migration.resources.contains("V7__add_parent_job_id_to_job_history.sql"))
        assertTrue(migration.resources.contains("V8__add_stale_status.sql"))
        assertTrue(migration.resources.contains("V9__scheduled_job_principals.sql"))
    }

    @Test
    fun `resources are in version order`() {
        val resources = migration.resources
        for (i in 0 until resources.size - 1) {
            val currentVersion = resources[i].substringBefore("__").removePrefix("V").toInt()
            val nextVersion = resources[i + 1].substringBefore("__").removePrefix("V").toInt()
            assertTrue(currentVersion < nextVersion, "Migration ${resources[i]} should come before ${resources[i + 1]}")
        }
    }

    @Test
    fun `principal migration uses lowercase postgres enum and durable state constraints`() {
        val sql = checkNotNull(javaClass.classLoader.getResource("db/migrations/V9__scheduled_job_principals.sql"))
            .readText()
            .lowercase()

        for (value in listOf("not_required", "needs_principal", "pending_confirmation", "active")) {
            assertTrue("'$value'" in sql)
        }
        assertTrue("create type scheduler.scheduled_job_principal_state as enum" in sql)
        assertTrue("and enabled = false" in sql)
        assertTrue("execution_principal_id is not null" in sql)
        assertTrue("principal_confirmed_by is not null" in sql)
    }
}
