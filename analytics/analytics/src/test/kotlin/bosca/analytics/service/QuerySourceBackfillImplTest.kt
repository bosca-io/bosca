package bosca.analytics.service

import bosca.analytics.model.AnalyticsQuery
import bosca.analytics.repository.QueryDefinitionRepository
import bosca.git.service.QuerySourceUpdate
import bosca.git.service.SourceRefSyncService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuerySourceBackfillImplTest {

    private val sourceRefSyncService = mockk<SourceRefSyncService>()
    private val queryRepository = mockk<QueryDefinitionRepository>()
    private val queryService = mockk<AnalyticsQueryService>()
    private val backfill = QuerySourceBackfillImpl(sourceRefSyncService, queryRepository, queryService)

    private val repositoryId = UUID.random()
    private val queryAId = UUID.random()
    private val queryBId = UUID.random()

    @Test
    fun `backfillRepository writes SQL for every query whose source has changed`() = runTest {
        val queryA = sampleQuery(queryAId, "select 1")
        val queryB = sampleQuery(queryBId, "select 2")
        coEvery { sourceRefSyncService.findAllQueriesAtHead(repositoryId) } returns listOf(
            QuerySourceUpdate(queryAId, "select 1 from a", "sha"),
            QuerySourceUpdate(queryBId, "select 2 from b", "sha"),
        )
        coEvery { queryRepository.getById(queryAId) } returns queryA
        coEvery { queryRepository.getById(queryBId) } returns queryB
        coEvery { queryService.applyGitSync(any(), any(), null) } answers {
            if (firstArg<UUID>() == queryAId) queryA else queryB
        }

        val count = backfill.backfillRepository(repositoryId)

        assertEquals(2, count)
        coVerify { queryService.applyGitSync(queryAId, "select 1 from a", null) }
        coVerify { queryService.applyGitSync(queryBId, "select 2 from b", null) }
    }

    @Test
    fun `backfillRepository does not count queries whose SQL is unchanged`() = runTest {
        val queryA = sampleQuery(queryAId, "select 1")
        coEvery { sourceRefSyncService.findAllQueriesAtHead(repositoryId) } returns listOf(
            QuerySourceUpdate(queryAId, queryA.query, "sha"),
        )
        coEvery { queryRepository.getById(queryAId) } returns queryA

        val count = backfill.backfillRepository(repositoryId)

        assertEquals(0, count)
        coVerify(exactly = 0) { queryService.applyGitSync(any(), any(), any()) }
    }

    @Test
    fun `backfillRepository skips updates whose query was deleted`() = runTest {
        coEvery { sourceRefSyncService.findAllQueriesAtHead(repositoryId) } returns listOf(
            QuerySourceUpdate(queryAId, "select 1", "sha"),
        )
        coEvery { queryRepository.getById(queryAId) } returns null

        val count = backfill.backfillRepository(repositoryId)

        assertEquals(0, count)
        coVerify(exactly = 0) { queryService.applyGitSync(any(), any(), any()) }
    }

    @Test
    fun `backfillQuery writes SQL and returns true when content differs`() = runTest {
        val queryA = sampleQuery(queryAId, "select 1")
        coEvery { sourceRefSyncService.findQueryAtHead(queryAId) } returns
            QuerySourceUpdate(queryAId, "select 1 v2", "sha")
        coEvery { queryRepository.getById(queryAId) } returns queryA
        coEvery { queryService.applyGitSync(queryAId, "select 1 v2", null) } returns queryA

        val changed = backfill.backfillQuery(queryAId)

        assertTrue(changed)
        coVerify { queryService.applyGitSync(queryAId, "select 1 v2", null) }
    }

    @Test
    fun `backfillQuery returns false when there is no source ref for the query`() = runTest {
        coEvery { sourceRefSyncService.findQueryAtHead(queryAId) } returns null

        val changed = backfill.backfillQuery(queryAId)

        assertFalse(changed)
        coVerify(exactly = 0) { queryService.applyGitSync(any(), any(), any()) }
    }

    @Test
    fun `backfillQuery returns false when the query is deleted during the update`() = runTest {
        val queryA = sampleQuery(queryAId, "select 1")
        coEvery { sourceRefSyncService.findQueryAtHead(queryAId) } returns
            QuerySourceUpdate(queryAId, "select 2", "sha")
        coEvery { queryRepository.getById(queryAId) } returns queryA
        coEvery { queryService.applyGitSync(queryAId, "select 2", null) } returns null

        assertFalse(backfill.backfillQuery(queryAId))
    }

    private fun sampleQuery(id: UUID, query: String) = AnalyticsQuery(
        id = id,
        key = "key-$id",
        name = "name",
        description = "desc",
        query = query,
        configuration = JsonNull,
    )
}
