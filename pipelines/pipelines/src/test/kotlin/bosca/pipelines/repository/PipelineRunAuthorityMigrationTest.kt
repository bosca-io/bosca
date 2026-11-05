package bosca.pipelines.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.Migration
import bosca.pipelines.model.PipelineRunAuthority
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals

/** V22 gives runs that predate captured authority the authority they executed under. */
class PipelineRunAuthorityMigrationTest {

    @Test
    fun `existing runs receive the authority they previously executed under`() = withDb {
        sql("drop schema if exists pipelines cascade")
        // Cross-schema prerequisites provided by the core/security migration in production.
        sql("create table if not exists groups (id uuid primary key)")
        sql("create table if not exists principal_groups (principal uuid, group_id uuid)")
        sql(
            """
            do ${'$'}${'$'} begin
                if not exists (select 1 from pg_type where typname = 'permission_action') then
                    create type permission_action as enum ('view', 'edit', 'manage', 'delete', 'execute');
                end if;
            end ${'$'}${'$'};
            """.trimIndent(),
        )
        val caller = UUID.random()
        val memberless = UUID.random()
        val groups = listOf(UUID.random(), UUID.random())
        sql("delete from principal_groups where principal in ('$caller', '$memberless')")
        groups.forEach { sql("insert into principal_groups (principal, group_id) values ('$caller', '$it')") }

        val pipelines = PipelinesMigration()
        FlywayMigration(pool).migrate(listOf(object : Migration {
            override val schema = pipelines.schema
            override val resources = pipelines.resources - setOf("V22__pipeline_run_authority.sql", "V23__pipeline_run_rollback_failure.sql")
        }))
        val pipelineId = UUID.random()
        sql("insert into pipelines.pipelines (id, name, accepted_input_type) values ('$pipelineId', 'p', 'sample')")
        val system = insertRun(pipelineId, null)
        val onDemand = insertRun(pipelineId, caller)
        val noGroups = insertRun(pipelineId, memberless)

        FlywayMigration(pool).migrate(listOf(pipelines))

        assertEquals(PipelineRunAuthority(system = true), authority(system))
        val captured = authority(onDemand)
        assertEquals(PipelineRunAuthority(system = false, groupIds = captured.groupIds), captured)
        assertEquals(groups.toSet(), captured.groupIds?.toSet())
        assertEquals(PipelineRunAuthority(system = false, groupIds = emptyList()), authority(noGroups))
    }

    private suspend fun insertRun(pipelineId: UUID, principalId: UUID?): UUID {
        val id = UUID.random()
        sql("insert into pipelines.pipeline_run (id, pipeline_id, graph_snapshot, principal_id) values " +
            "('$id', '$pipelineId', '{}'::jsonb, ${principalId?.let { "'$it'" } ?: "null"})")
        return id
    }

    private suspend fun authority(runId: UUID): PipelineRunAuthority =
        connection().useStatement("select authority::text from pipelines.pipeline_run where id = '$runId'") { statement ->
            statement.executeQuery().use { result ->
                check(result.next())
                json.decodeFromString(PipelineRunAuthority.serializer(), result.getString(1))
            }
        }

    private suspend fun sql(statement: String) {
        connection().useStatement(statement) { it.execute() }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg18").apply {
            withDatabaseName("bosca_pipelines_authority_migration")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 3),
                key = "authority-migration-test",
            ),
        )

        private val json = Json { serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()) } }
    }
}
