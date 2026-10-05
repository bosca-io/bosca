package bosca.git.ci.graphql

import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineStep
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GitPipelineSubscriptionSupportTest {
    private val jobService = mockk<PipelineJobService>()
    private val runService = mockk<PipelineRunService>()

    @Test
    fun `step from another repository is hidden`() = runTest {
        val requestedRepositoryId = UUID.random()
        val stepId = UUID.random()
        val jobId = UUID.random()
        val runId = UUID.random()
        val step = mockk<PipelineStep> { every { pipelineJobId } returns jobId }
        val job = mockk<PipelineJob> { every { pipelineRunId } returns runId }
        val run = mockk<PipelineRun> { every { repositoryId } returns UUID.random() }
        coEvery { jobService.findStepById(stepId) } returns step
        coEvery { jobService.findById(jobId) } returns job
        coEvery { runService.findById(runId) } returns run

        assertFailsWith<NoSuchElementException> {
            GitPipelineSubscriptionSupport.verifyStepRepository(
                requestedRepositoryId,
                stepId,
                jobService,
                runService,
            )
        }
    }
}
