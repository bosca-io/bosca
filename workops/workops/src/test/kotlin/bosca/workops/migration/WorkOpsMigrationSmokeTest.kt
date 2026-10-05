@file:OptIn(InternalDI::class)

package bosca.workops.migration

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.CoreMigration
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boots a real Postgres via TestContainers and applies the Work Ops Phase 1
 * migration against it. Asserts the dedicated `workops` schema is created
 * and that the migration is idempotent under repeated invocation.
 *
 * Phase 1 deliberately ships zero tables — V1 is a one-line schema bootstrap
 * — so the assertions check schema existence and Flyway bookkeeping rather
 * than table shapes. Phase 2 extends this test to assert the hierarchy /
 * task / history tables.
 *
 * Requires Docker, following the pattern used by
 * `bosca.collaboration.repository.MigrationSmokeTest`.
 */
class WorkOpsMigrationSmokeTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_migration_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 2,
                ),
                key = "workops-migration-test",
            )
        )

        private var initialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
    }

    @BeforeTest
    fun setup() {
        org.junit.Assume.assumeTrue(
            "Docker not available -- skipping Work Ops migration smoke test",
            isDockerAvailable(),
        )
        if (!initialized) {
            ProviderRegistry.clear()
            provides<ConnectionPool>(singleton = true) { pool }
            initialized = true
        }
    }

    private fun isDockerAvailable(): Boolean = try {
        org.testcontainers.DockerClientFactory.instance().isDockerAvailable
    } catch (_: Throwable) {
        false
    }

    private fun migrate(migrations: List<Migration>) {
        runBlocking {
            FlywayMigration(pool).migrate(migrations)
        }
    }

    private fun querySingleColumn(sql: String, column: String): List<String> =
        runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt ->
                        val rs = stmt.executeQuery()
                        val results = mutableListOf<String>()
                        while (rs.next()) results.add(rs.getString(column))
                        results
                    }
                }
            } finally {
                mgr.release()
            }
        }

    private fun querySingleLong(sql: String): Long =
        runBlocking {
            val mgr = pool.connection()
            try {
                withContext(mgr.asCoroutineContext()) {
                    connection().useStatement(sql) { stmt ->
                        val rs = stmt.executeQuery()
                        if (rs.next()) rs.getLong(1) else 0L
                    }
                }
            } finally {
                mgr.release()
            }
        }

    @Test
    fun `WorkOpsMigration creates the workops schema and the Phase 2-5+ table inventory`() {
        migrate(listOf(CoreMigration(), WorkOpsMigration()))

        val schemas = querySingleColumn(
            "select schema_name from information_schema.schemata where schema_name = 'workops'",
            "schema_name",
        )
        assertEquals(1, schemas.size, "workops schema should exist")

        // Listed by phase ownership: Phase 2 = hierarchy + lookups + task +
        // history. Phase 3 = workflow + links. The assertion names every base
        // table so a missing CREATE shows up as a precise diff.
        val tables = querySingleColumn(
            """
            select table_name from information_schema.tables
            where table_schema = 'workops' and table_name <> 'flyway_schema_history'
              and table_name not like 'task_history_%'
              and table_name not like 'spec_history_%'
              and table_name not like 'requirement_history_%'
              and table_name not like 'federation_payload_archive_%'
            order by table_name
            """.trimIndent(),
            "table_name",
        ).toSet()
        val expected = setOf(
            // Phase 2
            "portfolio",
            "program",
            "project",
            "project_analytics_application",
            "project_analytics_service",
            "project_key_counter",
            "task_key_alias",
            "task_type",
            "task_type_scheme",
            "status",
            "priority",
            "resolution",
            "task",
            "task_history",
            // Phase 3
            "workflow",
            "workflow_state",
            "workflow_transition",
            "workflow_scheme",
            "task_link_type",
            "task_link",
            // Phase 5
            "board",
            "board_column",
            "sprint",
            "version",
            "component",
            "label",
            "milestone",
            // Phase 4 (lands after Phase 5 in migration order)
            "task_comment",
            "task_comment_likes",
            "task_field_configuration_scheme",
            "task_field_configuration",
            // Phase 6
            "saved_filter",
            // Phase 7.B (notifications + watchers + outbox)
            "notification",
            "notification_outbox",
            "notification_preference",
            "notification_scheme",
            "notification_subscription",
            "task_watcher",
            // Phase 8.1 (SLA)
            "sla_goal",
            "sla_policy",
            "task_sla_state",
            "working_calendar",
            // Phase 8.2 (automation)
            "automation_execution_log",
            "automation_loop_guard",
            "automation_rule",
            // Phase 8.3 (worklogs)
            "worklog",
            // Phase 8.4 (attachments)
            "attachment",
            // Phase 9 (roadmap, OKRs, capacity)
            "capacity",
            "key_result",
            "objective",
            "roadmap_scenario",
            // Phase 16 (cross-project)
            "release",
            "release_component_version",
            "task_affected_project",
            "task_move_audit",
            // Phase 17 (calendar bindings, ceremonies)
            "calendar_binding",
            "recurring_ceremony",
            // Phase 14 (portal)
            "portal",
            "portal_request_type",
            "portal_token",
            "portal_user",
            // Phase 15 (email-in)
            "email_inbox",
            "inbound_email",
            "outbound_message_id",
            // Phase 13 (AI opt-in)
            "ai_org_settings",
            // Phase 21 (entity permissions)
            "portfolio_permissions",
            "program_permissions",
            "project_permissions",
            "project_repository",
            "task_permissions",
            // Phase 24 (federation)
            "federation_conflict",
            "federation_field_mask",
            "federation_payload_archive",
            "federation_peer",
            "federation_principal_mapping_proposal",
            // Phase 25 (multi-repo support)
            "dependency_declaration",
            "artifact_publication",
            "api_surface_report",
            "pipeline_run",
            "pipeline_stage_run",
            "environment",
            "environment_type",
            "environment_promotion_source",
            "environment_deployment",
            "release_gate",
            "compatibility_test_result",
            "release_notes",
            "spec_key_counter",
            "spec",
            "requirement_key_counter",
            "requirement",
            "spec_context",
            "spec_task_generation",
            "spec_comment",
            "spec_comment_likes",
            "spec_history",
            "requirement_history",
            "spec_permissions",
            "requirement_permissions",
            // Phase 30 (requirement comments)
            "requirement_comment",
            "requirement_comment_likes",
            // Phase 32 (multi-project boards)
            "board_project",
            // (release build graph → pipeline link)
            "release_pipeline",
            // (environment entity permissions)
            "environment_permissions",
            // (durable mobile-store build identities)
            "app_build_number_counter",
            "app_build_number_allocation",
        )
        val actual = tables.sorted()
        assertEquals(expected.sorted(), actual, "table inventory drifted")

        // Exactly 13 monthly partitions of `task_history` are pre-created
        // (current month + 12 forward); see V2 migration's DO block.
        val partitions = querySingleLong(
            """
            select count(*) from information_schema.tables
            where table_schema = 'workops' and table_name like 'task_history_%'
            """.trimIndent(),
        )
        assertEquals(13L, partitions, "expected 13 pre-created task_history partitions")

        val deploymentBuildNumberColumn = querySingleLong(
            """
            select count(*) from information_schema.columns
            where table_schema = 'workops' and table_name = 'environment_deployment'
              and column_name = 'app_build_number_allocation_id'
            """.trimIndent(),
        )
        assertEquals(1L, deploymentBuildNumberColumn, "deployment should retain its app build allocation")

        val buildNumberScopeColumns = querySingleLong(
            """
            select count(*) from information_schema.columns
            where table_schema = 'workops'
              and table_name in ('app_build_number_counter', 'app_build_number_allocation')
              and column_name = 'version_scope'
              and is_nullable = 'NO'
            """.trimIndent(),
        )
        assertEquals(2L, buildNumberScopeColumns, "build-number tables should persist the counter version scope")

        val deploymentTargetColumnType = querySingleColumn(
            """
            select udt_name from information_schema.columns
            where table_schema = 'workops' and table_name = 'environment_deployment'
              and column_name = 'target_kind'
            """.trimIndent(),
            "udt_name",
        )
        assertEquals(
            listOf("deploy_target_kind"),
            deploymentTargetColumnType,
            "deployment target kind must use the PostgreSQL enum",
        )
        val deploymentTargetKinds = querySingleColumn(
            """
            select enumlabel
            from pg_enum e
            join pg_type t on t.oid = e.enumtypid
            join pg_namespace n on n.oid = t.typnamespace
            where n.nspname = 'workops' and t.typname = 'deploy_target_kind'
            order by e.enumsortorder
            """.trimIndent(),
            "enumlabel",
        )
        assertEquals(
            listOf("helm", "helm_values", "google_play", "app_store"),
            deploymentTargetKinds,
        )
    }

    @Test
    fun `Phase 2 seeds the canonical task types, statuses, priorities, resolutions`() {
        migrate(listOf(CoreMigration(), WorkOpsMigration()))

        val taskTypes = querySingleColumn(
            "select name from workops.task_type order by name",
            "name",
        )
        assertEquals(
            listOf("Bug", "Epic", "Initiative", "Story", "Sub-task", "Task"),
            taskTypes,
            "R3 seeds drifted",
        )

        val statuses = querySingleColumn(
            "select name from workops.status order by name",
            "name",
        )
        assertEquals(
            listOf("Cancelled", "Done", "In Progress", "In Review", "To Do"),
            statuses,
            "R4 seed statuses drifted",
        )

        val priorities = querySingleColumn(
            "select name from workops.priority order by display_order",
            "name",
        )
        assertEquals(
            listOf("Lowest", "Low", "Medium", "High", "Highest", "Critical"),
            priorities,
            "Priority seed order drifted",
        )

        val resolutions = querySingleColumn(
            "select name from workops.resolution order by display_order",
            "name",
        )
        assertEquals(
            listOf("Done", "Fixed", "Won't Fix", "Duplicate", "Cannot Reproduce", "Incomplete", "Declined"),
            resolutions,
            "R9 resolutions seed drifted",
        )

        val schemes = querySingleLong("select count(*) from workops.task_type_scheme")
        assertEquals(1L, schemes, "Default Task Type Scheme should be seeded once")
    }

    @Test
    fun `WorkOpsMigration is idempotent`() {
        migrate(listOf(CoreMigration(), WorkOpsMigration()))
        val first = querySingleLong(
            "select count(*) from information_schema.schemata where schema_name = 'workops'",
        )
        migrate(listOf(CoreMigration(), WorkOpsMigration()))
        val second = querySingleLong(
            "select count(*) from information_schema.schemata where schema_name = 'workops'",
        )
        assertEquals(first, second, "schema count should be stable on reapply")
        assertEquals(1L, first, "workops schema should be present exactly once")
    }

}
