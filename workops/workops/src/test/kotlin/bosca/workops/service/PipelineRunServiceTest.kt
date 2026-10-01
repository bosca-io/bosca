package bosca.workops.service

import bosca.serialization.UUID
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.pipeline.PipelineStageRun
import bosca.workops.model.pipeline.PipelineStatus
import bosca.workops.model.pipeline.PipelineTriggerType
import bosca.workops.repository.PipelineRunRepository
import bosca.workops.repository.PipelineStageRunRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PipelineRunServiceTest {

    private val runRepository = mockk<PipelineRunRepository>()
    private val stageRepository = mockk<PipelineStageRunRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val programRepository = mockk<ProgramRepository>()
    private val dispatcher = mockk<AutomationDispatcher>()
    private val service = PipelineRunServiceImpl(
        runRepository,
        stageRepository,
        projectRepository,
        programRepository,
        dispatcher,
    )

    @Test
    fun `run and stage lookups delegate to their repositories`() = runTest {
        val run = PipelineRun(
            id = UUID.random(),
            projectId = UUID.random(),
            pipelineId = "release",
            pipelineName = "Release",
            triggerType = PipelineTriggerType.TAG,
        )
        val stage = PipelineStageRun(
            id = UUID.random(),
            pipelineRunId = run.id,
            stageName = "test",
        )
        coEvery { runRepository.getById(run.id) } returns run
        coEvery { stageRepository.getById(stage.id) } returns stage

        assertEquals(run, service.getById(run.id))
        assertEquals(stage, service.getStageById(stage.id))
    }

    @Test
    fun `completing a missing run reports a typed not found failure`() = runTest {
        val id = UUID.random()
        coEvery { runRepository.updateStatus(id, PipelineStatus.PASSED.name, any(), 4) } returns null

        val failure = assertFailsWith<WorkOpsNotFoundException> {
            service.complete(id, PipelineStatus.PASSED, 4)
        }

        assertEquals("PipelineRun", failure.type)
        assertEquals(id.toString(), failure.handle)
    }

    @Test
    fun `completing a missing stage reports a typed not found failure`() = runTest {
        val id = UUID.random()
        coEvery { stageRepository.updateStatus(id, PipelineStatus.FAILED.name, any()) } returns null

        val failure = assertFailsWith<WorkOpsNotFoundException> {
            service.completeStage(id, PipelineStatus.FAILED)
        }

        assertEquals("PipelineStageRun", failure.type)
        assertEquals(id.toString(), failure.handle)
    }

    @Test
    fun `completion survives an automation dispatch failure`() = runTest {
        val run = PipelineRun(
            id = UUID.random(),
            projectId = UUID.random(),
            pipelineId = "release",
            pipelineName = "Release",
            triggerType = PipelineTriggerType.TAG,
        )
        val completed = run.copy(status = PipelineStatus.PASSED, version = 2)
        coEvery { runRepository.updateStatus(run.id, PipelineStatus.PASSED.name, any(), 1) } returns completed
        coEvery { projectRepository.getById(run.projectId) } returns null
        coEvery {
            dispatcher.firePipelineCompleted(run.projectId, null, null, run.pipelineId)
        } throws IllegalStateException("automation unavailable")

        assertEquals(completed, service.complete(run.id, PipelineStatus.PASSED, 1))
    }
}
