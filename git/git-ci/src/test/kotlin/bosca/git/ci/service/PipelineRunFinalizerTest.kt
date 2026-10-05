@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.ci.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Run finalization uses the job service's verified terminal outcome. */
class PipelineRunFinalizerTest {

    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val finalizer = PipelineRunFinalizer(jobService, runService, agentService)

    private val runId = UUID.random()
    private val jobId = UUID.random()
    private fun job(status: PipelineRunStatus = PipelineRunStatus.SUCCESS) = PipelineJob(
        id = jobId, pipelineRunId = runId, name = "build-and-publish", status = status,
    )

    private fun run() = PipelineRun(
        id = runId, pipelineId = UUID.random(), repositoryId = UUID.random(),
        commitSha = "abc", ref = "refs/tags/v6.0.5", triggerType = PipelineTriggerType.TAG,
        status = PipelineRunStatus.RUNNING, number = 1,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        coEvery { runService.findById(runId) } returns run()
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `a success report that failed artifact verification finalizes the stored failure`() = runTest {
        coEvery { jobService.findById(jobId) } returns job(PipelineRunStatus.FAILURE)
        coEvery { jobService.findByRun(runId) } returns listOf(job(PipelineRunStatus.FAILURE))
        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)
        coVerify(exactly = 1) { jobService.cancelBlockedJobs(runId) }
        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.FAILURE) }
    }

    @Test
    fun `a verified successful job finalizes success without repeating verification`() = runTest {
        coEvery { jobService.findById(jobId) } returns job()
        coEvery { jobService.findByRun(runId) } returns listOf(job())
        finalizer.finalizeJob(jobId, PipelineRunStatus.SUCCESS, releaseAgent = false)
        coVerify(exactly = 1) { runService.updateStatus(runId, PipelineRunStatus.SUCCESS) }
    }
}
