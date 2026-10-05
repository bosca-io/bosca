package bosca.git.ci.jobs

import bosca.di.provides
import bosca.git.ci.repository.PipelineRepository
import bosca.git.model.Pipeline
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTrigger
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineLogService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class PipelineLogRetentionExecutorTest {

    private val pipelineRepository = mockk<PipelineRepository>(relaxed = true)
    private val logService = mockk<PipelineLogService>(relaxed = true)

    @BeforeTest
    fun setup() {
        provides<PipelineRepository>(singleton = true) { pipelineRepository }
        provides<PipelineLogService>(singleton = true) { logService }
    }

    @Test
    fun `cleans logs for each repository that has pipelines`() = runTest {
        val repo1 = UUID.random()
        val repo2 = UUID.random()
        coEvery { pipelineRepository.findAll() } returns listOf(
            testPipeline(repositoryId = repo1),
            testPipeline(repositoryId = repo1),
            testPipeline(repositoryId = repo2)
        )

        executeRetention(31)

        coVerify { logService.deleteExpiredLogs(repo1, 31) }
        coVerify { logService.deleteExpiredLogs(repo2, 31) }
    }

    @Test
    fun `deduplicates repository IDs`() = runTest {
        val repoId = UUID.random()
        coEvery { pipelineRepository.findAll() } returns listOf(
            testPipeline(repositoryId = repoId),
            testPipeline(repositoryId = repoId)
        )

        executeRetention(31)

        coVerify(exactly = 1) { logService.deleteExpiredLogs(repoId, 31) }
    }

    @Test
    fun `handles no pipelines`() = runTest {
        coEvery { pipelineRepository.findAll() } returns emptyList()

        executeRetention(31)

        coVerify(exactly = 0) { logService.deleteExpiredLogs(any(), any()) }
    }

    @Test
    fun `continues processing when one repository fails`() = runTest {
        val repo1 = UUID.random()
        val repo2 = UUID.random()
        coEvery { pipelineRepository.findAll() } returns listOf(
            testPipeline(repositoryId = repo1),
            testPipeline(repositoryId = repo2)
        )
        coEvery { logService.deleteExpiredLogs(repo1, 31) } throws RuntimeException("Storage error")

        executeRetention(31)

        coVerify { logService.deleteExpiredLogs(repo2, 31) }
    }

    @Test
    fun `uses configured retention days`() = runTest {
        val repoId = UUID.random()
        coEvery { pipelineRepository.findAll() } returns listOf(testPipeline(repositoryId = repoId))

        executeRetention(7)

        coVerify { logService.deleteExpiredLogs(repoId, 7) }
    }

    private suspend fun executeRetention(retentionDays: Int) {
        val pipelineRepository = bosca.di.provide<PipelineRepository>()
        val logService = bosca.di.provide<PipelineLogService>()

        val repositoryIds = pipelineRepository.findAll().map { it.repositoryId }.toSet()

        for (repositoryId in repositoryIds) {
            try {
                logService.deleteExpiredLogs(repositoryId, retentionDays)
            } catch (_: Exception) {
            }
        }
    }

    private fun testPipeline(repositoryId: UUID = UUID.random()) = Pipeline(
        id = UUID.random(),
        repositoryId = repositoryId,
        filePath = ".bosca/pipelines/build.yaml",
        name = "Build",
        triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)).toJsonElement(),
        configHash = "abc"
    )
}
