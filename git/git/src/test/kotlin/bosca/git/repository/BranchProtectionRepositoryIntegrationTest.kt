@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.repository

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepository
import bosca.git.model.BranchProtectionRule
import bosca.git.service.BranchProtectionServiceImpl
import bosca.git.transport.GitPreReceiveHook
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.ReceiveCommand
import org.eclipse.jgit.transport.ReceivePack
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Exercises branch-protection lookup through the generated JDBC repository and the real
 * pre-receive hook. This pins the production boundary that mock-only receive-pack tests cannot
 * cover: a repository may contain a protected `main` rule while a new, unrelated branch remains
 * pushable.
 */
class BranchProtectionRepositoryIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("branch_protection_test")
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
                key = "branch-protection-test",
            )
        )
    }

    private val repositoryId = UUID.random()
    private val repository = BranchProtectionRepositoryImpl()
    private val service = BranchProtectionServiceImpl(repository)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json }

        withDb {
            connection().useStatement("drop schema if exists git cascade") { it.execute() }
            connection().useStatement(
                """
                create schema git;
                create table git.repositories (
                    id uuid primary key
                );
                create table git.branch_protection_rules (
                    id uuid primary key default gen_random_uuid(),
                    repository_id uuid not null references git.repositories(id) on delete cascade,
                    pattern varchar not null,
                    require_pull_request boolean not null default false,
                    required_approvals int not null default 1,
                    dismiss_stale_reviews boolean not null default false,
                    require_code_owner_review boolean not null default false,
                    require_status_checks varchar[] not null default '{}',
                    require_linear_history boolean not null default false,
                    allow_force_push boolean not null default false,
                    allow_deletion boolean not null default false,
                    restrict_push_access uuid[] not null default '{}',
                    created timestamptz not null default now(),
                    updated timestamptz not null default now()
                );
                insert into git.repositories (id) values ('$repositoryId'::uuid);
                """.trimIndent()
            ) { it.execute() }
        }
    }

    @Test
    fun `protected main does not reject a new unrelated branch`() = withDb {
        repository.create(
            BranchProtectionRule(
                repositoryId = repositoryId,
                pattern = "main",
                requirePullRequest = true,
            )
        )

        val boscaRepository = mockk<BoscaDfsRepository>(relaxed = true)
        every { boscaRepository.repositoryId } returns repositoryId
        val receivePack = mockk<ReceivePack>(relaxed = true)
        every { receivePack.repository } returns boscaRepository
        val hook = GitPreReceiveHook(service)

        val featureCommand = ReceiveCommand(
            ObjectId.zeroId(),
            ObjectId.fromString("a".repeat(40)),
            "refs/heads/kjb/test",
        )
        hook.onPreReceive(receivePack, mutableListOf(featureCommand))
        assertEquals(ReceiveCommand.Result.NOT_ATTEMPTED, featureCommand.result)

        val mainCommand = ReceiveCommand(
            ObjectId.fromString("a".repeat(40)),
            ObjectId.fromString("b".repeat(40)),
            "refs/heads/main",
        )
        hook.onPreReceive(receivePack, mutableListOf(mainCommand))
        assertEquals(ReceiveCommand.Result.REJECTED_OTHER_REASON, mainCommand.result)
    }

    @Test
    fun `API file commits enforce persisted rules and actual identity permissions`() = withDb {
        val principalId = UUID.random()
        val authentication = bosca.security.service.ImpersonatedAuthenticationContext(
            bosca.security.model.Principal(id = principalId, verified = true, anonymous = false),
            listOf(bosca.security.model.Group(UUID.random(), "editors", "", bosca.security.model.GroupType.SYSTEM)),
        )
        val denied = bosca.security.service.ImpersonatedAuthenticationContext(
            bosca.security.model.Principal(id = UUID.random(), verified = true, anonymous = false), emptyList(),
        )
        val repo = bosca.git.model.Repository(id = repositoryId, slug = "r", name = "R", ownerId = UUID.random(), visibility = bosca.git.model.Visibility.PRIVATE)
        val repoService = mockk<bosca.git.service.RepositoryService>()
        coEvery { repoService.findById(repositoryId) } returns repo
        coEvery { repoService.getPermissions(repo) } returns emptyList()
        coEvery { repoService.isParentAllowed(any(), repo, any()) } returns false
        val security = mockk<bosca.security.service.SecurityService>()
        val groups = bosca.security.service.GroupEvaluator(security)
        val evaluator = bosca.git.security.RepositoryPermissionEvaluator(repoService, security, groups)
        val git = org.eclipse.jgit.internal.storage.dfs.InMemoryRepository(org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription("api-protection"))
        val manager = mockk<bosca.git.dfs.BoscaDfsRepositoryManager>()
        every { manager.open(repositoryId) } returns git
        val lock = mockk<bosca.lock.DistributedLock>(relaxed = true)
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.release() } returns true
        val locks = mockk<bosca.lock.DistributedLockFactory>()
        coEvery { locks.create(any()) } returns lock
        val writes = bosca.git.service.RepositoryWriteServiceImpl(manager, mockk(relaxed = true), locks)
        val mutation = bosca.git.graphql.GitRepositoryMutation(repoService, evaluator, service, mockk(), mockk(), mockk(), writes, mockk(), mockk(), groups, mockk(), mockk(), mockk())
        val input = bosca.git.graphql.GraphQLCommitFileInput(repositoryId, "main", "README.md", "# Private\n", "Initial", "Author", "author@example.test")
        try {
            val rule = service.create(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true))
            assertFailsWith<IllegalStateException> { mutation.commitFile(authentication, input) }
            assertFailsWith<IllegalStateException> { mutation.deleteFile(authentication, repositoryId, "main", "README.md", "Author", "author@example.test") }
            assertNull(git.resolve("refs/heads/main"))
            assertFailsWith<bosca.security.service.SecurityException> { mutation.commitFile(denied, input.copy(branch = "feature/new")) }
            assertNull(git.resolve("refs/heads/feature/new"))
            val commit = mutation.commitFile(authentication, input.copy(branch = "feature/new"))
            assertEquals(commit.commitSha, assertNotNull(git.resolve("refs/heads/feature/new")).name())
            service.update(rule.copy(requirePullRequest = false, restrictPushAccess = listOf(UUID.random())))
            assertFailsWith<IllegalStateException> { mutation.commitFile(authentication, input) }
            service.update(rule.copy(requirePullRequest = false, restrictPushAccess = listOf(principalId)))
            val allowed = mutation.commitFile(authentication, input)
            assertEquals(allowed.commitSha, assertNotNull(git.resolve("refs/heads/main")).name())
            mutation.deleteFile(authentication, repositoryId, "main", "README.md", "Author", "author@example.test")
            org.eclipse.jgit.revwalk.RevWalk(git).use { walk ->
                val head = walk.parseCommit(git.resolve("refs/heads/main"))
                assertNull(org.eclipse.jgit.treewalk.TreeWalk.forPath(git, "README.md", head.tree))
            }
        } finally { git.close() }
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
