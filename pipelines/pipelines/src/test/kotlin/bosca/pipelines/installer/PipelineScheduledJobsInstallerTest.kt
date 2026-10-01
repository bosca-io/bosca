package bosca.pipelines.installer

import bosca.pipelines.trigger.PipelineRetentionSweepExecutor
import bosca.pipelines.trigger.PipelineSuspendedSweepExecutor
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test

/**
 * The installer registers the periodic pipeline jobs (suspended-run sweep + run-retention sweep) as cron
 * jobs — idempotently and independently: each is created when absent and skipped when already present
 * (so re-running the installer never duplicates either).
 */
class PipelineScheduledJobsInstallerTest {

    private val json = Json

    @Test
    fun `registers both sweep jobs when neither is scheduled`() = runTest {
        val scheduler = mockk<SchedulerService>(relaxed = true)
        coEvery { scheduler.getJobs() } returns emptyList()

        PipelineScheduledJobsInstaller(scheduler, json).install(mockk(relaxed = true), mockk(relaxed = true))

        coVerify(exactly = 1) {
            scheduler.createJob(match { it.jobName == PipelineSuspendedSweepExecutor.NAME }, any())
        }
        coVerify(exactly = 1) {
            scheduler.createJob(match { it.jobName == PipelineRetentionSweepExecutor.NAME }, any())
        }
    }

    @Test
    fun `creates only the missing job — the retention sweep — when the suspended sweep already exists`() = runTest {
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val existing = mockk<ScheduledJob> { every { jobName } returns PipelineSuspendedSweepExecutor.NAME }
        coEvery { scheduler.getJobs() } returns listOf(existing)

        PipelineScheduledJobsInstaller(scheduler, json).install(mockk(relaxed = true), mockk(relaxed = true))

        coVerify(exactly = 0) {
            scheduler.createJob(match { it.jobName == PipelineSuspendedSweepExecutor.NAME }, any())
        }
        coVerify(exactly = 1) {
            scheduler.createJob(match { it.jobName == PipelineRetentionSweepExecutor.NAME }, any())
        }
    }

    @Test
    fun `skips registration when both sweep jobs already exist`() = runTest {
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val suspended = mockk<ScheduledJob> { every { jobName } returns PipelineSuspendedSweepExecutor.NAME }
        val retention = mockk<ScheduledJob> { every { jobName } returns PipelineRetentionSweepExecutor.NAME }
        coEvery { scheduler.getJobs() } returns listOf(suspended, retention)

        PipelineScheduledJobsInstaller(scheduler, json).install(mockk(relaxed = true), mockk(relaxed = true))

        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }
}
