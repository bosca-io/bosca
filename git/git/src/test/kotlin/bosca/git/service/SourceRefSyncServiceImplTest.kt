@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.QuerySourceRef
import bosca.git.model.ScriptSourceRef
import bosca.git.repository.QuerySourceRefRepository
import bosca.git.repository.ScriptSourceRefRepository
import bosca.serialization.UUID
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test for the read-side of git source-ref sync, exercising
 * the full path through a real JGit repository (in-memory DFS), a real
 * [RepositoryBrowseServiceImpl], and a real [SourceRefSyncServiceImpl].
 * Only the source-ref repositories (which hit Postgres) are mocked.
 */
class SourceRefSyncServiceImplTest {

    private val repositoryId = UUID.random()
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val packRepository = mockk<bosca.git.repository.DfsPackRepository>(relaxed = true)
    private val searchService = mockk<bosca.search.service.SearchService>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val cacheSerializer = mockk<RequestCacheSerializer>(relaxed = true)
    private val scriptRefs = mockk<ScriptSourceRefRepository>()
    private val queryRefs = mockk<QuerySourceRefRepository>()
    private lateinit var browseService: RepositoryBrowseServiceImpl
    private lateinit var service: SourceRefSyncServiceImpl

    private lateinit var repo: InMemoryRepository
    private var firstCommitId: ObjectId = ObjectId.zeroId()
    private var secondCommitId: ObjectId = ObjectId.zeroId()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<RequestCacheSerializer>(singleton = true) { cacheSerializer }

        repo = InMemoryRepository(DfsRepositoryDescription("test"))
        createTestContent()
        every { dfsManager.open(repositoryId) } returns repo

        browseService = RepositoryBrowseServiceImpl(dfsManager, packRepository, searchService)
        service = SourceRefSyncServiceImpl(scriptRefs, queryRefs, browseService)
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    private suspend fun <T> withCache(block: suspend () -> T): T {
        val cache = RequestCache(cacheManager, cacheSerializer)
        return withContext(cache.asCoroutineContext()) { block() }
    }

    @Test
    fun `findAffectedQueries returns update only for paths that changed`() = runTest {
        val changedQueryId = UUID.random()
        val unchangedQueryId = UUID.random()
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(changedQueryId, repositoryId, "queries/changed.sql", "main"),
            QuerySourceRef(unchangedQueryId, repositoryId, "queries/unchanged.sql", "main"),
        )
        coEvery { queryRefs.updateResolvedCommit(changedQueryId, any()) } just Runs

        val updates = withCache {
            service.findAffectedQueries(
                repositoryId = repositoryId,
                pushedRef = "refs/heads/main",
                beforeSha = firstCommitId.name(),
                afterSha = secondCommitId.name(),
            )
        }

        assertEquals(1, updates.size)
        assertEquals(changedQueryId, updates[0].queryId)
        assertEquals("select 2", updates[0].newQuery)
        assertEquals(secondCommitId.name(), updates[0].commitSha)
        coVerify { queryRefs.updateResolvedCommit(changedQueryId, secondCommitId.name()) }
        coVerify(exactly = 0) { queryRefs.updateResolvedCommit(unchangedQueryId, any()) }
    }

    @Test
    fun `findAffectedQueries returns empty when afterSha is the zero id`() = runTest {
        val updates = service.findAffectedQueries(
            repositoryId = repositoryId,
            pushedRef = "refs/heads/main",
            beforeSha = firstCommitId.name(),
            afterSha = ObjectId.zeroId().name(),
        )
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findAffectedQueries treats branch creation as full sync`() = runTest {
        val queryId = UUID.random()
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(queryId, repositoryId, "queries/unchanged.sql", "main"),
        )
        coEvery { queryRefs.updateResolvedCommit(queryId, any()) } just Runs

        val updates = withCache {
            service.findAffectedQueries(
                repositoryId = repositoryId,
                pushedRef = "refs/heads/main",
                beforeSha = ObjectId.zeroId().name(),
                afterSha = secondCommitId.name(),
            )
        }

        assertEquals(1, updates.size)
        assertEquals("select 1", updates[0].newQuery)
    }

    @Test
    fun `findAffectedQueries skips refs whose branch does not match`() = runTest {
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(UUID.random(), repositoryId, "queries/changed.sql", "develop"),
        )

        val updates = withCache {
            service.findAffectedQueries(
                repositoryId = repositoryId,
                pushedRef = "refs/heads/main",
                beforeSha = firstCommitId.name(),
                afterSha = secondCommitId.name(),
            )
        }
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `findAllQueriesAtHead resolves HEAD and reads all linked files`() = runTest {
        val queryId = UUID.random()
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(queryId, repositoryId, "queries/unchanged.sql", "main"),
        )
        coEvery { queryRefs.updateResolvedCommit(queryId, any()) } just Runs

        val updates = withCache {
            service.findAllQueriesAtHead(repositoryId)
        }

        assertEquals(1, updates.size)
        assertEquals(queryId, updates[0].queryId)
        assertEquals("select 1", updates[0].newQuery)
        assertEquals(secondCommitId.name(), updates[0].commitSha)
    }

    @Test
    fun `findQueryAtHead returns null when the query has no source ref`() = runTest {
        val queryId = UUID.random()
        coEvery { queryRefs.findByQueryId(queryId) } returns null

        val update = service.findQueryAtHead(queryId)
        assertNull(update)
    }

    @Test
    fun `findQueryAtHead reads the linked file at HEAD`() = runTest {
        val queryId = UUID.random()
        coEvery { queryRefs.findByQueryId(queryId) } returns
            QuerySourceRef(queryId, repositoryId, "queries/changed.sql", "main")
        coEvery { queryRefs.updateResolvedCommit(queryId, any()) } just Runs

        val update = withCache {
            service.findQueryAtHead(queryId)
        }

        assertNotNull(update)
        assertEquals(queryId, update.queryId)
        assertEquals("select 2", update.newQuery)
        coVerify { queryRefs.updateResolvedCommit(queryId, secondCommitId.name()) }
    }

    @Test
    fun `findAllScriptsAtHead mirrors the query path for scripts`() = runTest {
        val scriptId = UUID.random()
        coEvery { scriptRefs.findByRepository(repositoryId) } returns listOf(
            ScriptSourceRef(scriptId, repositoryId, "queries/changed.sql", "main"),
        )
        coEvery { scriptRefs.updateResolvedCommit(scriptId, any()) } just Runs

        val updates = withCache {
            service.findAllScriptsAtHead(repositoryId)
        }

        assertEquals(1, updates.size)
        assertEquals(scriptId, updates[0].scriptId)
        assertEquals("select 2", updates[0].newSource)
    }

    private fun createTestContent() {
        val inserter = repo.objectDatabase.newInserter()
        val author = PersonIdent("Test Author", "test@example.com")

        val changedV1 = inserter.insert(Constants.OBJ_BLOB, "select 1".toByteArray())
        val unchanged = inserter.insert(Constants.OBJ_BLOB, "select 1".toByteArray())
        val rootV1 = TreeFormatter().apply {
            append("queries", FileMode.TREE, inserter.insert(TreeFormatter().apply {
                append("changed.sql", FileMode.REGULAR_FILE, changedV1)
                append("unchanged.sql", FileMode.REGULAR_FILE, unchanged)
            }))
        }
        val rootTreeV1 = inserter.insert(rootV1)
        firstCommitId = inserter.insert(CommitBuilder().apply {
            setTreeId(rootTreeV1)
            setAuthor(author)
            setCommitter(author)
            setMessage("Initial")
        })

        val changedV2 = inserter.insert(Constants.OBJ_BLOB, "select 2".toByteArray())
        val rootV2 = TreeFormatter().apply {
            append("queries", FileMode.TREE, inserter.insert(TreeFormatter().apply {
                append("changed.sql", FileMode.REGULAR_FILE, changedV2)
                append("unchanged.sql", FileMode.REGULAR_FILE, unchanged)
            }))
        }
        val rootTreeV2 = inserter.insert(rootV2)
        secondCommitId = inserter.insert(CommitBuilder().apply {
            setTreeId(rootTreeV2)
            setAuthor(author)
            setCommitter(author)
            setMessage("Update changed.sql")
            setParentId(firstCommitId)
        })
        inserter.flush()

        repo.refDatabase.newUpdate("refs/heads/main", true).apply {
            setNewObjectId(secondCommitId)
        }.update()
        repo.refDatabase.newUpdate(Constants.HEAD, true).link("refs/heads/main")
    }

    // ── appended coverage: script push-sync, unresolvable refs, missing files ──

    @Test
    fun `findAffectedScripts syncs matching script refs on a branch push`() = runTest {
        val scriptId = UUID.random()
        coEvery { scriptRefs.findByRepository(repositoryId) } returns listOf(
            ScriptSourceRef(scriptId, repositoryId, "queries/changed.sql", "main"),
        )
        coEvery { scriptRefs.updateResolvedCommit(scriptId, any()) } just Runs

        val updates = withCache {
            service.findAffectedScripts(repositoryId, "refs/heads/main", firstCommitId.name(), secondCommitId.name())
        }

        assertEquals(1, updates.size)
        assertEquals(scriptId, updates[0].scriptId)
        coVerify { scriptRefs.updateResolvedCommit(scriptId, secondCommitId.name()) }
    }

    @Test
    fun `findAffectedScripts returns empty for deletes and non-matching branches`() = runTest {
        assertEquals(
            emptyList(),
            withCache { service.findAffectedScripts(repositoryId, "refs/heads/main", firstCommitId.name(), "0".repeat(40)) },
        )

        coEvery { scriptRefs.findByRepository(repositoryId) } returns listOf(
            ScriptSourceRef(UUID.random(), repositoryId, "queries/changed.sql", "other-branch"),
        )
        assertEquals(
            emptyList(),
            withCache { service.findAffectedScripts(repositoryId, "refs/heads/main", firstCommitId.name(), secondCommitId.name()) },
        )
    }

    @Test
    fun `findAffectedScripts warns and skips paths missing at the new commit`() = runTest {
        val scriptId = UUID.random()
        coEvery { scriptRefs.findByRepository(repositoryId) } returns listOf(
            ScriptSourceRef(scriptId, repositoryId, "queries/changed.sql", "main"),
            ScriptSourceRef(UUID.random(), repositoryId, "queries/missing.sql", "main"),
        )
        coEvery { scriptRefs.updateResolvedCommit(any(), any()) } just Runs

        // Branch creation (zero before-sha) forces a full sync of all candidates.
        val updates = withCache {
            service.findAffectedScripts(repositoryId, "refs/heads/main", "0".repeat(40), secondCommitId.name())
        }

        assertEquals(listOf(scriptId), updates.map { it.scriptId })
    }

    @Test
    fun `head lookups skip refs that do not resolve`() = runTest {
        val scriptId = UUID.random()
        val queryId = UUID.random()
        coEvery { scriptRefs.findByRepository(repositoryId) } returns listOf(
            ScriptSourceRef(scriptId, repositoryId, "queries/changed.sql", "no-such-branch"),
        )
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(queryId, repositoryId, "queries/changed.sql", "no-such-branch"),
        )
        assertEquals(emptyList(), withCache { service.findAllScriptsAtHead(repositoryId) })
        assertEquals(emptyList(), withCache { service.findAllQueriesAtHead(repositoryId) })

        coEvery { scriptRefs.findByScriptId(scriptId) } returns
            ScriptSourceRef(scriptId, repositoryId, "queries/changed.sql", "no-such-branch")
        coEvery { queryRefs.findByQueryId(queryId) } returns
            QuerySourceRef(queryId, repositoryId, "queries/changed.sql", "no-such-branch")
        assertEquals(null, withCache { service.findScriptAtHead(scriptId) })
        assertEquals(null, withCache { service.findQueryAtHead(queryId) })
    }

    @Test
    fun `findScriptAtHead resolves the linked file and records the commit`() = runTest {
        val scriptId = UUID.random()
        coEvery { scriptRefs.findByScriptId(scriptId) } returns
            ScriptSourceRef(scriptId, repositoryId, "queries/changed.sql", "main")
        coEvery { scriptRefs.updateResolvedCommit(scriptId, any()) } just Runs

        val update = withCache { service.findScriptAtHead(scriptId) }

        assertEquals(scriptId, update?.scriptId)
        assertEquals("select 2", update?.newSource)
    }

    @Test
    fun `findScriptAtHead returns null without a source ref or with a missing file`() = runTest {
        val scriptId = UUID.random()
        coEvery { scriptRefs.findByScriptId(scriptId) } returns null
        assertEquals(null, withCache { service.findScriptAtHead(scriptId) })

        coEvery { scriptRefs.findByScriptId(scriptId) } returns
            ScriptSourceRef(scriptId, repositoryId, "queries/missing.sql", "main")
        assertEquals(null, withCache { service.findScriptAtHead(scriptId) })
    }

    @Test
    fun `findAllScriptsAtHead skips files missing at head`() = runTest {
        val present = UUID.random()
        val absent = UUID.random()
        coEvery { scriptRefs.findByRepository(repositoryId) } returns listOf(
            ScriptSourceRef(present, repositoryId, "queries/changed.sql", "main"),
            ScriptSourceRef(absent, repositoryId, "queries/missing.sql", "main"),
        )
        coEvery { scriptRefs.updateResolvedCommit(any(), any()) } just Runs

        val updates = withCache { service.findAllScriptsAtHead(repositoryId) }
        assertEquals(listOf(present), updates.map { it.scriptId })
    }

    @Test
    fun `query paths mirror the script arms for skips and missing files`() = runTest {
        // Unchanged path skipped on an incremental push (changedFiles filter arm).
        val skipped = UUID.random()
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(skipped, repositoryId, "queries/unchanged.sql", "main"),
        )
        assertEquals(
            emptyList(),
            withCache { service.findAffectedQueries(repositoryId, "refs/heads/main", firstCommitId.name(), secondCommitId.name()) },
        )

        // Full sync (branch creation) with one present and one missing file.
        val present = UUID.random()
        val absent = UUID.random()
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(present, repositoryId, "queries/changed.sql", "main"),
            QuerySourceRef(absent, repositoryId, "queries/missing.sql", "main"),
        )
        coEvery { queryRefs.updateResolvedCommit(any(), any()) } just Runs
        val updates = withCache {
            service.findAffectedQueries(repositoryId, "refs/heads/main", "0".repeat(40), secondCommitId.name())
        }
        assertEquals(listOf(present), updates.map { it.queryId })
    }

    @Test
    fun `head lookups return empty when no refs are registered`() = runTest {
        coEvery { queryRefs.findByRepository(repositoryId) } returns emptyList()
        coEvery { scriptRefs.findByRepository(repositoryId) } returns emptyList()
        assertEquals(emptyList(), withCache { service.findAllQueriesAtHead(repositoryId) })
        assertEquals(emptyList(), withCache { service.findAllScriptsAtHead(repositoryId) })
    }

    @Test
    fun `findAllQueriesAtHead skips files missing at head and findQueryAtHead rejects missing files`() = runTest {
        val present = UUID.random()
        val absent = UUID.random()
        coEvery { queryRefs.findByRepository(repositoryId) } returns listOf(
            QuerySourceRef(present, repositoryId, "queries/changed.sql", "main"),
            QuerySourceRef(absent, repositoryId, "queries/missing.sql", "main"),
        )
        coEvery { queryRefs.updateResolvedCommit(any(), any()) } just Runs
        val updates = withCache { service.findAllQueriesAtHead(repositoryId) }
        assertEquals(listOf(present), updates.map { it.queryId })

        coEvery { queryRefs.findByQueryId(absent) } returns
            QuerySourceRef(absent, repositoryId, "queries/missing.sql", "main")
        assertEquals(null, withCache { service.findQueryAtHead(absent) })
    }
}
