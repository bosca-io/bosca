package bosca.git.ci.graphql

import bosca.git.model.Pipeline
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineStep
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.service.PipelineArtifactService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineScheduleService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitPipelineRunControllerTest {

    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val artifactService = mockk<PipelineArtifactService>(relaxed = true)
    private lateinit var controller: GitPipelineRunController

    @BeforeTest
    fun setup() {
        controller = GitPipelineRunController(jobService, artifactService)
    }

    @Test
    fun `jobs returns jobs for a run`() = runTest {
        val run = testRun()
        val jobs = listOf(
            testJob(name = "build"),
            testJob(name = "test")
        )
        coEvery { jobService.findByRun(run.id) } returns jobs

        val result = controller.jobs(run)
        assertEquals(2, result.size)
    }

    @Test
    fun `durationSeconds returns null when not started`() {
        val run = testRun(started = null, finished = null)
        assertNull(controller.durationSeconds(run))
    }

    @Test
    fun `durationSeconds returns null when not finished`() {
        val run = testRun(finished = null)
        assertNull(controller.durationSeconds(run))
    }

    private fun testRun(
        started: bosca.serialization.OffsetDateTime? = bosca.serialization.OffsetDateTime.now(),
        finished: bosca.serialization.OffsetDateTime? = bosca.serialization.OffsetDateTime.now()
    ) = PipelineRun(
        id = UUID.random(),
        pipelineId = UUID.random(),
        repositoryId = UUID.random(),
        commitSha = "abc",
        ref = "main",
        triggerType = PipelineTriggerType.PUSH,
        status = PipelineRunStatus.SUCCESS,
        number = 1,
        started = started,
        finished = finished
    )

    private fun testJob(name: String = "build") = PipelineJob(
        id = UUID.random(),
        pipelineRunId = UUID.random(),
        name = name,
        runnerLabel = "linux"
    )
}

class GitPipelineControllerTest {

    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val scheduleService = mockk<PipelineScheduleService>(relaxed = true)
    private val json = Json
    private lateinit var controller: GitPipelineController

    @BeforeTest
    fun setup() {
        controller = GitPipelineController(runService, scheduleService, json)
    }

    @Test
    fun `runs returns runs for a pipeline with defaults`() = runTest {
        val pipeline = testPipeline()
        coEvery { runService.findByPipeline(pipeline.id, 0, 25) } returns emptyList()

        val result = controller.runs(pipeline, null, null)
        assertEquals(0, result.size)
    }

    @Test
    fun `runs coerces limit to valid range`() = runTest {
        val pipeline = testPipeline(filePath = "a.yaml", name = "A")
        coEvery { runService.findByPipeline(pipeline.id, 0, 100) } returns emptyList()

        controller.runs(pipeline, null, 500)
    }

    @Test
    fun `triggerTypes decodes stored triggers into distinct types`() {
        val pipeline = testPipeline(
            triggers = listOf(
                PipelineTrigger(type = PipelineTriggerType.TAG, tags = listOf("*")),
                PipelineTrigger(type = PipelineTriggerType.MANUAL),
                PipelineTrigger(type = PipelineTriggerType.TAG, tags = listOf("v*"))
            )
        )

        val result = controller.triggerTypes(pipeline)
        assertEquals(listOf(PipelineTriggerType.TAG, PipelineTriggerType.MANUAL), result)
    }

    @Test
    fun `triggerTypes returns empty for a pipeline with no triggers`() {
        val pipeline = testPipeline()
        assertEquals(emptyList(), controller.triggerTypes(pipeline))
    }

    private fun testPipeline(
        filePath: String = ".bosca/pipelines/build.yaml",
        name: String = "Build",
        triggers: List<PipelineTrigger> = emptyList()
    ) = Pipeline(
        id = UUID.random(),
        repositoryId = UUID.random(),
        filePath = filePath,
        name = name,
        triggers = json.encodeToJsonElement(ListSerializer(PipelineTrigger.serializer()), triggers),
        configHash = "abc"
    )
}

class GitPipelineJobControllerTest {

    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private lateinit var controller: GitPipelineJobController

    @BeforeTest
    fun setup() {
        controller = GitPipelineJobController(jobService, mockk(relaxed = true))
    }

    @Test
    fun `steps returns steps for a job`() = runTest {
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "build",
            runnerLabel = "linux"
        )
        val steps = listOf(
            PipelineStep(id = UUID.random(), pipelineJobId = job.id, name = "Checkout", ordinal = 0),
            PipelineStep(id = UUID.random(), pipelineJobId = job.id, name = "Build", ordinal = 1)
        )
        coEvery { jobService.getSteps(job.id) } returns steps

        val result = controller.steps(job)
        assertEquals(2, result.size)
        assertEquals("Checkout", result[0].name)
    }
}
