@file:OptIn(ExperimentalUuidApi::class, bosca.di.annotation.InternalDI::class)

package bosca.pipelines.graphql

import bosca.di.ObjectProvider
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.pipelines.model.Pipeline
import bosca.pipelines.service.PipelineService
import bosca.pipelines.trigger.PipelineScheduledRunExecutor
import bosca.pipelines.trigger.PipelineScheduledRunJob
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.service.SchedulerService
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi

/**
 * The pipeline's cron `schedule` is mirrored into a SchedulerService ScheduledJob on save/delete:
 * create when first scheduled, update on change, delete when cleared or
 * the pipeline is removed. These drive the mutation controller with a controlled scheduler and assert
 * the right scheduler call for each transition.
 */
class PipelineScheduleSyncTest {

    private val json = Json

    @AfterTest
    fun tearDown() = ProviderRegistry.clear()

    private fun pipeline(schedule: String?) =
        Pipeline(id = UUID.random(), name = "p", acceptedInputType = "JSON", schedule = schedule)

    /** A ScheduledJob standing in for an already-registered schedule of [pipelineId]. */
    private fun existingJob(pipelineId: UUID) = ScheduledJob(
        id = UUID.random(),
        name = "Pipeline schedule: p",
        jobName = PipelineScheduledRunExecutor.NAME,
        jobParameters = json.encodeToJsonElement(PipelineScheduledRunJob.serializer(), PipelineScheduledRunJob(pipelineId)),
        cronExpression = "0 8 * * *",
        createdAt = java.time.OffsetDateTime.now(),
        updatedAt = java.time.OffsetDateTime.now(),
        createdBy = UUID.NIL,
    )

    private fun controller(service: PipelineService, scheduler: SchedulerService): PipelinesMutationController {
        provides<Json> { json } // syncSchedule resolves Json via provide<Json>()
        val schedulerProvider = mockk<ObjectProvider<SchedulerService>>()
        every { schedulerProvider.exists } returns true
        coEvery { schedulerProvider.get() } returns scheduler
        return PipelinesMutationController(
            service, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), schedulerProvider,
            mockk(relaxed = true),
        )
    }

    @Suppress("LongParameterList")
    private fun PipelineService.stubSaveReturns(saved: Pipeline) {
        coEvery {
            save(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns saved
    }

    @Test
    fun `saving a newly-scheduled pipeline creates a scheduler job`() = runTest {
        val service = mockk<PipelineService>()
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val saved = pipeline(schedule = "0 9 * * *")
        service.stubSaveReturns(saved)
        coEvery { scheduler.getJobs(any(), any(), any()) } returns emptyList() // none yet

        controller(service, scheduler).save(mockk<AuthenticationContext>(), PipelineInput(name = "p", acceptedInputType = "JSON", schedule = "0 9 * * *", graph = JsonObject(emptyMap())))

        coVerify(exactly = 1) {
            scheduler.createJob(match { it.jobName == PipelineScheduledRunExecutor.NAME && it.cronExpression == "0 9 * * *" }, any())
        }
        coVerify(exactly = 0) { scheduler.updateJob(any(), any()) }
        coVerify(exactly = 0) { scheduler.deleteJob(any()) }
    }

    @Test
    fun `saving a changed schedule updates the existing job`() = runTest {
        val service = mockk<PipelineService>()
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val saved = pipeline(schedule = "0 10 * * *")
        service.stubSaveReturns(saved)
        val job = existingJob(saved.id)
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(job)

        controller(service, scheduler).save(mockk<AuthenticationContext>(), PipelineInput(name = "p", acceptedInputType = "JSON", schedule = "0 10 * * *", graph = JsonObject(emptyMap())))

        coVerify(exactly = 1) { scheduler.updateJob(job.id, match { it.cronExpression == "0 10 * * *" }) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `clearing the schedule deletes the existing job`() = runTest {
        val service = mockk<PipelineService>()
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val saved = pipeline(schedule = null) // schedule removed
        service.stubSaveReturns(saved)
        val job = existingJob(saved.id)
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(job)

        controller(service, scheduler).save(mockk<AuthenticationContext>(), PipelineInput(name = "p", acceptedInputType = "JSON", graph = JsonObject(emptyMap())))

        coVerify(exactly = 1) { scheduler.deleteJob(job.id) }
        coVerify(exactly = 0) { scheduler.createJob(any(), any()) }
    }

    @Test
    fun `deleting a pipeline tears down its scheduler job`() = runTest {
        val service = mockk<PipelineService>(relaxed = true)
        val scheduler = mockk<SchedulerService>(relaxed = true)
        val existing = pipeline(schedule = "0 9 * * *")
        coEvery { service.get(existing.id) } returns existing
        val job = existingJob(existing.id)
        coEvery { scheduler.getJobs(any(), any(), any()) } returns listOf(job)

        controller(service, scheduler).delete(mockk<AuthenticationContext>(), existing.id)

        coVerify(exactly = 1) { service.delete(existing.id) }
        coVerify(exactly = 1) { scheduler.deleteJob(job.id) }
    }
}
