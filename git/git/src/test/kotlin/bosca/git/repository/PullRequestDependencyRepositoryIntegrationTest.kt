@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PullRequestStatus
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/** Exercises the generated dependency repository against PostgreSQL using the production migrations. */
class PullRequestDependencyRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("pull_request_dependency_test")
            withReuse(true)
            start()
        }

        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 4,
                ),
                key = "pull-request-dependency-test",
            )
        )
    }

    private val parentRepositoryId = UUID.random()
    private val dependencyRepositoryId = UUID.random()
    private val parentId = UUID.random()
    private val dependencyId = UUID.random()
    private val repository = PullRequestDependencyRepositoryImpl()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }

        withDb {
            connection().useStatement("drop schema if exists git cascade") { it.execute() }
            connection().useStatement("create schema git; create table git.repositories (id uuid primary key, configuration jsonb not null default '{}');") {
                it.execute()
            }
            runMigration("V3__pull_requests.sql")
            runMigration("V49__pull_request_merge_base.sql")
            runMigration("V50__pull_request_branch_cleanup.sql")
            connection().useStatement("alter table git.pull_requests add column version bigint not null default 0") { it.execute() }
            runMigration("V37__pull_request_dependencies.sql")
            connection().useStatement(
                """
                insert into git.repositories (id) values
                    ('$parentRepositoryId'::uuid),
                    ('$dependencyRepositoryId'::uuid);
                insert into git.pull_requests
                    (id, repository_id, number, title, author_id, source_branch, target_branch)
                values
                    ('$parentId'::uuid, '$parentRepositoryId'::uuid, 7, 'Workspace', gen_random_uuid(), 'workspace', 'main'),
                    ('$dependencyId'::uuid, '$dependencyRepositoryId'::uuid, 4, 'Git', gen_random_uuid(), 'git', 'main');
                """.trimIndent()
            ) { it.execute() }
        }
    }

    @Test
    fun `stores and resolves a cross-repository dependency in both directions`() = withDb {
        repository.add(parentId, dependencyId)

        assertEquals(listOf(dependencyId), repository.findDependencies(parentId).map { it.id })
        assertEquals(listOf(parentId), repository.findDependents(dependencyId).map { it.id })

        repository.remove(parentId, dependencyId)
        assertEquals(emptyList(), repository.findDependencies(parentId))
        assertEquals(emptyList(), repository.findDependents(dependencyId))
    }

    @Test
    fun `branch cleanup detects active source and target references including forks`() = withDb {
        val prs = PullRequestRepositoryImpl()
        assertTrue(prs.isBranchInUse(parentRepositoryId, "workspace"))
        assertTrue(prs.isBranchInUse(parentRepositoryId, "main"))
        assertFalse(prs.isBranchInUse(dependencyRepositoryId, "workspace"))
        assertFalse(prs.isBranchInUse(parentRepositoryId, "unused"))

        prs.updateStatus(parentId, PullRequestStatus.DRAFT, 0)
        assertTrue(prs.isBranchInUse(parentRepositoryId, "workspace"))
        assertTrue(prs.isBranchInUse(parentRepositoryId, "main"))

        connection().useStatement(
            "update git.pull_requests set source_repository_id = '$dependencyRepositoryId' where id = '$parentId'"
        ) { it.execute() }
        assertFalse(prs.isBranchInUse(parentRepositoryId, "workspace"))
        assertTrue(prs.isBranchInUse(dependencyRepositoryId, "workspace"))
        assertTrue(prs.isBranchInUse(parentRepositoryId, "main"))

        prs.updateStatus(parentId, PullRequestStatus.CLOSED, 1)
        assertFalse(prs.isBranchInUse(dependencyRepositoryId, "workspace"))
        assertFalse(prs.isBranchInUse(parentRepositoryId, "main"))
        prs.updateStatus(dependencyId, PullRequestStatus.MERGED, 0)
        assertFalse(prs.isBranchInUse(dependencyRepositoryId, "git"))
        assertFalse(prs.isBranchInUse(dependencyRepositoryId, "main"))
    }

    @Test
    fun `migration snapshots repository defaults and PR updates persist independent choices`() = withDb {
        connection().useStatement("alter table git.pull_requests drop column delete_branch_on_merge") { it.execute() }
        connection().useStatement("""
            update git.repositories set configuration = '{"deleteBranchOnMerge":false}'
            where id = '$parentRepositoryId'
        """) { it.execute() }
        runMigration("V50__pull_request_branch_cleanup.sql")
        val prs = PullRequestRepositoryImpl()
        val parent = assertNotNull(prs.findById(parentId))
        assertFalse(parent.deleteBranchOnMerge)
        assertTrue(assertNotNull(prs.findById(dependencyId)).deleteBranchOnMerge)
        connection().useStatement("update git.repositories set configuration = '{}' where id = '$parentRepositoryId'") { it.execute() }
        assertFalse(assertNotNull(prs.findById(parentId)).deleteBranchOnMerge)
        val enabled = assertNotNull(prs.update(parent.copy(deleteBranchOnMerge = true)))
        assertTrue(assertNotNull(prs.findById(parentId)).deleteBranchOnMerge)
        assertNotNull(prs.update(enabled.copy(deleteBranchOnMerge = false)))
        assertFalse(assertNotNull(prs.findById(parentId)).deleteBranchOnMerge)
    }

    private suspend fun runMigration(name: String) {
        val sql = javaClass.getResource("/db/migrations/$name")?.readText()
            ?: error("Migration not found: $name")
        connection().useStatement(sql) { it.execute() }
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) {
                    block()
                }
            } finally {
                withContext(NonCancellable) {
                    manager.release()
                }
            }
        }
    }
}
