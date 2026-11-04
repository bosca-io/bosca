package bosca.git.ci.service

import bosca.git.model.Pipeline
import bosca.git.model.PipelineScheduleJob
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobInput
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PipelineScheduleServiceImplTest {

    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val json = Json
    private val service = PipelineScheduleServiceImpl(schedulerService, json)
    private val pipeline = Pipeline(
        id = UUID.random(),
        repositoryId = UUID.random(),
        filePath = ".bosca/pipelines/nightly.yaml",
        name = "Nightly",
        configHash = "hash",
    )

    @Test
    fun `sync creates one principal-required scheduler job per distinct nonblank cron`() = runTest {
        coEvery { schedulerService.getJobsByName(PipelineScheduleJob.NAME, 10_000, 0) } returns emptyList()
        val captured = mutableListOf<ScheduledJobInput>()
        coEvery { schedulerService.createJob(capture(captured), UUID.NIL) } answers {
            scheduledJob(firstArg<ScheduledJobInput>().cronExpression)
        }

        val result = service.sync(
            pipeline,
            listOf(
                PipelineTrigger(PipelineTriggerType.PUSH),
                PipelineTrigger(PipelineTriggerType.SCHEDULE),
                PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = " 0 3 * * * "),
                PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = "0 3 * * *"),
                PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = " "),
                PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = "0 4 * * *"),
            ),
        )

        assertEquals(listOf("0 3 * * *", "0 4 * * *"), result.map { it.cronExpression })
        assertEquals(2, captured.size)
        captured.forEach { input ->
            assertEquals(PipelineScheduleJob.NAME, input.jobName)
            assertEquals(true, input.requiresPrincipal)
            assertEquals(true, input.enabled)
            assertEquals(false, input.allowConcurrent)
            assertEquals(false, input.catchUp)
            assertEquals(1, input.maxCatchUp)
            assertEquals(
                pipeline.id,
                json.decodeFromJsonElement(PipelineScheduleJob.serializer(), input.jobParameters).pipelineId,
            )
        }
    }

    @Test
    fun `sync retains exact jobs updates metadata drift and deletes removed cron`() = runTest {
        val retained = scheduledJob("0 1 * * *")
        val duplicate = retained.copy(id = UUID.random())
        val drifted = scheduledJob("0 2 * * *").copy(name = "old name")
        val stale = scheduledJob("0 3 * * *")
        coEvery { schedulerService.getJobsByName(PipelineScheduleJob.NAME, 10_000, 0) } returns
            listOf(retained, duplicate, drifted, stale)
        coEvery { schedulerService.updateJob(drifted.id, any()) } answers {
            drifted.copy(name = secondArg<ScheduledJobInput>().name)
        }

        val result = service.sync(
            pipeline,
            listOf(
                PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = retained.cronExpression),
                PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = drifted.cronExpression),
            ),
        )

        assertSame(retained, result[0])
        assertEquals("Git pipeline schedule: Nightly", result[1].name)
        coVerify { schedulerService.deleteJob(duplicate.id) }
        coVerify { schedulerService.deleteJob(stale.id) }
        coVerify(exactly = 1) { schedulerService.updateJob(drifted.id, any()) }
        coVerify(exactly = 0) { schedulerService.createJob(any(), any()) }
    }

    @Test
    fun `sync fails if an existing scheduler row disappears during update`() = runTest {
        val drifted = scheduledJob("0 2 * * *").copy(description = "old")
        coEvery { schedulerService.getJobsByName(PipelineScheduleJob.NAME, 10_000, 0) } returns listOf(drifted)
        coEvery { schedulerService.updateJob(drifted.id, any()) } returns null

        kotlin.test.assertFailsWith<NoSuchElementException> {
            service.sync(pipeline, listOf(PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = drifted.cronExpression)))
        }
    }

    @Test
    fun `sync repairs every scheduler-owned configuration field without replacing assignment`() = runTest {
        val cron = "0 2 * * *"
        val expected = scheduledJob(cron)
        val variants = listOf(
            expected.copy(description = "old"),
            expected.copy(allowConcurrent = true),
            expected.copy(catchUp = true),
            expected.copy(maxCatchUp = 2),
            expected.copy(principalState = ScheduledJobPrincipalState.NOT_REQUIRED),
        )
        coEvery { schedulerService.getJobsByName(PipelineScheduleJob.NAME, 10_000, 0) } returnsMany
            variants.map(::listOf)
        coEvery { schedulerService.updateJob(any(), any()) } returns expected

        repeat(variants.size) {
            service.sync(pipeline, listOf(PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = cron)))
        }

        coVerify(exactly = variants.size) { schedulerService.updateJob(any(), any()) }
    }

    @Test
    fun `find and delete only expose git pipeline scheduler jobs`() = runTest {
        val schedule = scheduledJob("0 1 * * *")
        val foreign = schedule.copy(id = UUID.random(), jobName = "other")
        coEvery { schedulerService.getJob(schedule.id) } returns schedule
        coEvery { schedulerService.getJob(foreign.id) } returns foreign

        assertSame(schedule, service.findById(schedule.id))
        assertNull(service.findById(foreign.id))
        assertNull(service.findById(UUID.random()))
        service.delete(schedule.id)
        service.delete(foreign.id)

        coVerify(exactly = 1) { schedulerService.deleteJob(schedule.id) }
        coVerify(exactly = 0) { schedulerService.deleteJob(foreign.id) }
    }

    @Test
    fun `pipeline lookup and deletion ignore foreign and malformed payloads`() = runTest {
        val ours = scheduledJob("0 1 * * *")
        val another = scheduledJob("0 2 * * *", UUID.random())
        val malformed = scheduledJob("0 3 * * *").copy(jobParameters = JsonObject(emptyMap()))
        coEvery { schedulerService.getJobsByName(PipelineScheduleJob.NAME, 10_000, 0) } returns
            listOf(ours, another, malformed)

        assertEquals(listOf(ours), service.findByPipeline(pipeline.id))
        service.deleteByPipeline(pipeline.id)

        coVerify(exactly = 1) { schedulerService.deleteJob(ours.id) }
        coVerify(exactly = 0) { schedulerService.deleteJob(another.id) }
        coVerify(exactly = 0) { schedulerService.deleteJob(malformed.id) }
    }

    private fun scheduledJob(cron: String, pipelineId: UUID = pipeline.id) = ScheduledJob(
        id = UUID.random(),
        name = "Git pipeline schedule: ${pipeline.name}",
        description = "Runs ${pipeline.filePath} as its explicitly assigned principal.",
        jobName = PipelineScheduleJob.NAME,
        jobParameters = json.encodeToJsonElement(PipelineScheduleJob.serializer(), PipelineScheduleJob(pipelineId)),
        cronExpression = cron,
        enabled = false,
        allowConcurrent = false,
        catchUp = false,
        maxCatchUp = 1,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        createdBy = UUID.NIL,
        principalState = ScheduledJobPrincipalState.NEEDS_PRINCIPAL,
    )
}
