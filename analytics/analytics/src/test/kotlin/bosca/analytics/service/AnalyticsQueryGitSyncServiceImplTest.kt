package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.model.AnalyticsQueryParameter
import bosca.analytics.model.QueryParameterType
import bosca.analytics.query.QueryParameterDeclaration
import bosca.analytics.query.QuerySourceParseException
import bosca.analytics.repository.QueryDefinitionRepository
import bosca.git.service.QuerySourceUpdate
import bosca.git.service.RepositoryWriteService
import bosca.git.service.SourceRefService
import bosca.git.service.SourceRefSyncService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyticsQueryGitSyncServiceImplTest {

    private val sourceRefSyncService = mockk<SourceRefSyncService>()
    private val sourceRefService = mockk<SourceRefService>()
    private val queryRepository = mockk<QueryDefinitionRepository>()
    private val queryService = mockk<AnalyticsQueryService>()
    private val repositoryWriteService = mockk<RepositoryWriteService>()
    private val service = AnalyticsQueryGitSyncServiceImpl(
        sourceRefSyncService,
        sourceRefService,
        queryRepository,
        queryService,
        repositoryWriteService,
    )

    private val repositoryId = UUID.random()
    private val queryId = UUID.random()

    @Test
    fun `onPushEvent forwards a plain SQL update to applyGitSync with null declarations`() = runTest {
        val newSql = "select 2"
        coEvery {
            sourceRefSyncService.findAffectedQueries(repositoryId, "refs/heads/main", "before", "after")
        } returns listOf(QuerySourceUpdate(queryId, newSql, "after"))
        coEvery { queryService.applyGitSync(queryId, newSql, null) } returns sampleQuery(query = newSql)

        service.onPushEvent(repositoryId, "refs/heads/main", "before", "after")

        coVerify { queryService.applyGitSync(queryId, newSql, null) }
    }

    @Test
    fun `onPushEvent ignores orphan source refs whose query no longer exists`() = runTest {
        coEvery {
            sourceRefSyncService.findAffectedQueries(repositoryId, "refs/heads/main", "before", "after")
        } returns listOf(QuerySourceUpdate(queryId, "select 1", "after"))
        coEvery { queryService.applyGitSync(queryId, "select 1", null) } returns null

        service.onPushEvent(repositoryId, "refs/heads/main", "before", "after")

        coVerify { queryService.applyGitSync(queryId, "select 1", null) }
    }

    @Test
    fun `onPushEvent is a no-op when no source refs were affected`() = runTest {
        coEvery {
            sourceRefSyncService.findAffectedQueries(repositoryId, "refs/heads/main", "before", "after")
        } returns emptyList()

        service.onPushEvent(repositoryId, "refs/heads/main", "before", "after")

        coVerify(exactly = 0) { queryService.applyGitSync(any(), any(), any()) }
    }

    @Test
    fun `onPushEvent strips a bosca-query block and forwards parameter declarations`() = runTest {
        val newSqlWithBlock = """
            /* @bosca-query
            { "parameters": [
              { "parameter": "startDate", "name": "Start", "type": "DATE", "required": true }
            ] }
            */
            select * from events where created >= :startDate
        """.trimIndent()
        coEvery {
            sourceRefSyncService.findAffectedQueries(repositoryId, "refs/heads/main", "before", "after")
        } returns listOf(QuerySourceUpdate(queryId, newSqlWithBlock, "after"))
        var capturedDecls: List<QueryParameterDeclaration>? = null
        coEvery {
            queryService.applyGitSync(queryId, "select * from events where created >= :startDate", any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            capturedDecls = invocation.args[2] as List<QueryParameterDeclaration>?
            sampleQuery(query = "select * from events where created >= :startDate")
        }

        service.onPushEvent(repositoryId, "refs/heads/main", "before", "after")

        val decls = assertNotNull(capturedDecls)
        assertEquals(1, decls.size)
        assertEquals("startDate", decls.single().parameter)
        assertEquals(QueryParameterType.DATE, decls.single().type)
        assertEquals(true, decls.single().required)
    }

    @Test
    fun `onPushEvent forwards null declarations when no bosca-query block is present`() = runTest {
        coEvery {
            sourceRefSyncService.findAffectedQueries(repositoryId, "refs/heads/main", "before", "after")
        } returns listOf(QuerySourceUpdate(queryId, "select 2", "after"))
        var capturedDecls: List<QueryParameterDeclaration>? = listOf()
        coEvery {
            queryService.applyGitSync(queryId, "select 2", any())
        } coAnswers {
            @Suppress("UNCHECKED_CAST")
            capturedDecls = invocation.args[2] as List<QueryParameterDeclaration>?
            sampleQuery(query = "select 2")
        }

        service.onPushEvent(repositoryId, "refs/heads/main", "before", "after")

        assertNull(capturedDecls)
    }

    @Test
    fun `onPushEvent skips a query with a malformed block but applies the rest then throws`() = runTest {
        val otherQueryId = UUID.random()
        val malformed = "/* @bosca-query\nNOT JSON\n*/\nselect 2"
        coEvery {
            sourceRefSyncService.findAffectedQueries(repositoryId, "refs/heads/main", "before", "after")
        } returns listOf(
            QuerySourceUpdate(queryId, malformed, "after"),
            QuerySourceUpdate(otherQueryId, "select 99", "after"),
        )
        coEvery { queryService.applyGitSync(otherQueryId, "select 99", null) } returns sampleQuery(
            query = "select 99",
            id = otherQueryId,
        )

        assertFailsWith<QuerySourceParseException> {
            service.onPushEvent(repositoryId, "refs/heads/main", "before", "after")
        }
        coVerify { queryService.applyGitSync(otherQueryId, "select 99", null) }
        coVerify(exactly = 0) { queryService.applyGitSync(queryId, any(), any()) }
    }

    @Test
    fun `pushToGit commits plain SQL when the query has no parameters`() = runTest {
        val query = sampleQuery("select 1")
        val sourceRef = bosca.git.model.QuerySourceRef(
            queryId = queryId,
            repositoryId = repositoryId,
            path = "queries/foo.sql",
            ref = "main",
        )
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns sourceRef
        coEvery { queryRepository.getById(queryId) } returns query
        coEvery { queryService.getParameters(queryId) } returns emptyList()
        coEvery { repositoryWriteService.commitFile(any()) } returns bosca.git.service.CommitFileResult(
            commitSha = "abc123",
            branch = "main",
            path = "queries/foo.sql",
        )

        val sha = service.pushToGit(queryId, "Author", "author@example.com")

        assertEquals("abc123", sha)
        coVerify {
            repositoryWriteService.commitFile(match {
                it.repositoryId == repositoryId &&
                    it.branch == "main" &&
                    it.path == "queries/foo.sql" &&
                    it.content == "select 1" &&
                    it.authorName == "Author" &&
                    it.authorEmail == "author@example.com"
            })
        }
    }

    @Test
    fun `pushToGit prepends a bosca-query block when the query has parameters`() = runTest {
        val query = sampleQuery("select * from events where created >= :startDate")
        val sourceRef = bosca.git.model.QuerySourceRef(
            queryId = queryId,
            repositoryId = repositoryId,
            path = "queries/foo.sql",
            ref = "main",
        )
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns sourceRef
        coEvery { queryRepository.getById(queryId) } returns query
        coEvery { queryService.getParameters(queryId) } returns listOf(
            AnalyticsQueryParameter(
                queryId = queryId,
                parameter = "startDate",
                name = "Start date",
                description = "Range start",
                type = QueryParameterType.DATE,
                arrayType = QueryParameterType.NONE,
                defaultValue = null,
                required = true,
                sort = 0,
            )
        )
        coEvery { repositoryWriteService.commitFile(any()) } returns bosca.git.service.CommitFileResult(
            commitSha = "abc123",
            branch = "main",
            path = "queries/foo.sql",
        )

        service.pushToGit(queryId, "Author", "author@example.com")

        coVerify {
            repositoryWriteService.commitFile(match {
                it.content.startsWith("/* @bosca-query\n") &&
                    it.content.contains("\"startDate\"") &&
                    it.content.contains("\"Start date\"") &&
                    it.content.endsWith("\nselect * from events where created >= :startDate")
            })
        }
    }

    @Test
    fun `pushToGit returns null when there is no source ref`() = runTest {
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns null

        val sha = service.pushToGit(queryId, "Author", "author@example.com")

        assertEquals(null, sha)
        coVerify(exactly = 0) { repositoryWriteService.commitFile(any()) }
    }

    @Test
    fun `pushToGit returns null when the linked query was deleted`() = runTest {
        val sourceRef = bosca.git.model.QuerySourceRef(queryId, repositoryId, "queries/deleted.sql", "main")
        coEvery { sourceRefService.findQuerySourceRef(queryId) } returns sourceRef
        coEvery { queryRepository.getById(queryId) } returns null

        assertNull(service.pushToGit(queryId, "Author", "author@example.com"))
        coVerify(exactly = 0) { repositoryWriteService.commitFile(any()) }
    }

    @Test
    fun `pushAllToGit commits every git-backed query in the repository`() = runTest {
        val queryA = UUID.random()
        val queryB = UUID.random()
        val refA = bosca.git.model.QuerySourceRef(queryA, repositoryId, "queries/a.sql", "main")
        val refB = bosca.git.model.QuerySourceRef(queryB, repositoryId, "queries/b.sql", "main")

        coEvery { sourceRefService.findQuerySourceRefsByRepository(repositoryId) } returns listOf(refA, refB)
        coEvery { sourceRefService.findQuerySourceRef(queryA) } returns refA
        coEvery { sourceRefService.findQuerySourceRef(queryB) } returns refB
        coEvery { queryRepository.getById(queryA) } returns sampleQuery("select 1", id = queryA)
        coEvery { queryRepository.getById(queryB) } returns sampleQuery("select 2", id = queryB)
        coEvery { queryService.getParameters(queryA) } returns emptyList()
        coEvery { queryService.getParameters(queryB) } returns emptyList()
        coEvery { repositoryWriteService.commitFile(any()) } returns bosca.git.service.CommitFileResult(
            commitSha = "sha", branch = "main", path = "queries/a.sql",
        )

        val count = service.pushAllToGit(repositoryId, "Author", "author@example.com")

        assertEquals(2, count)
        coVerify(exactly = 2) { repositoryWriteService.commitFile(any()) }
    }

    @Test
    fun `pushAllToGit skips queries whose source ref was removed between listing and push`() = runTest {
        val queryA = UUID.random()
        val refA = bosca.git.model.QuerySourceRef(queryA, repositoryId, "queries/a.sql", "main")

        coEvery { sourceRefService.findQuerySourceRefsByRepository(repositoryId) } returns listOf(refA)
        coEvery { sourceRefService.findQuerySourceRef(queryA) } returns null

        val count = service.pushAllToGit(repositoryId, "Author", "author@example.com")

        assertEquals(0, count)
        coVerify(exactly = 0) { repositoryWriteService.commitFile(any()) }
    }

    @Test
    fun `pushAllToGit isolates per-query failures and returns the partial success count`() = runTest {
        val queryA = UUID.random()
        val queryB = UUID.random()
        val queryC = UUID.random()
        val refA = bosca.git.model.QuerySourceRef(queryA, repositoryId, "queries/a.sql", "main")
        val refB = bosca.git.model.QuerySourceRef(queryB, repositoryId, "queries/b.sql", "main")
        val refC = bosca.git.model.QuerySourceRef(queryC, repositoryId, "queries/c.sql", "main")

        coEvery { sourceRefService.findQuerySourceRefsByRepository(repositoryId) } returns listOf(refA, refB, refC)
        coEvery { sourceRefService.findQuerySourceRef(queryA) } returns refA
        coEvery { sourceRefService.findQuerySourceRef(queryB) } returns refB
        coEvery { sourceRefService.findQuerySourceRef(queryC) } returns refC
        coEvery { queryRepository.getById(queryA) } returns sampleQuery("select a", id = queryA)
        coEvery { queryRepository.getById(queryB) } returns sampleQuery("select b", id = queryB)
        coEvery { queryRepository.getById(queryC) } returns sampleQuery("select c", id = queryC)
        coEvery { queryService.getParameters(queryA) } returns emptyList()
        coEvery { queryService.getParameters(queryB) } returns emptyList()
        coEvery { queryService.getParameters(queryC) } returns emptyList()

        // B fails; A and C should still commit.
        coEvery {
            repositoryWriteService.commitFile(match { it.path == "queries/a.sql" })
        } returns bosca.git.service.CommitFileResult("sha-a", "main", "queries/a.sql")
        coEvery {
            repositoryWriteService.commitFile(match { it.path == "queries/b.sql" })
        } throws RuntimeException("auth denied")
        coEvery {
            repositoryWriteService.commitFile(match { it.path == "queries/c.sql" })
        } returns bosca.git.service.CommitFileResult("sha-c", "main", "queries/c.sql")

        val count = service.pushAllToGit(repositoryId, "Author", "author@example.com")

        assertEquals(2, count)
        coVerify { repositoryWriteService.commitFile(match { it.path == "queries/a.sql" }) }
        coVerify { repositoryWriteService.commitFile(match { it.path == "queries/c.sql" }) }
    }

    private fun sampleQuery(query: String, id: UUID = queryId) = AnalyticsQuery(
        id = id,
        key = "key",
        name = "name",
        description = "desc",
        query = query,
        configuration = JsonNull,
    )
}
