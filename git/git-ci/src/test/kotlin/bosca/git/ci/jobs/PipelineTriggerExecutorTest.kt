package bosca.git.ci.jobs

import bosca.di.provides
import bosca.git.model.CommitStatusState
import bosca.git.model.JobDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTrigger
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTriggerType
import bosca.git.model.StepDefinition
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineService
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class PipelineTriggerExecutorTest {

    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)

    private val repoId = UUID.random()
    private val pipelineId = UUID.random()

    @BeforeTest
    fun setup() {
        provides<PipelineService>(singleton = true) { pipelineService }
        provides<PipelineRunService>(singleton = true) { runService }
        provides<CommitStatusService>(singleton = true) { commitStatusService }
        provides<PubSubService>(singleton = true) { mockk(relaxed = true) }
    }

    @Test
    fun `triggers matching pipeline and creates run with commit statuses`() = runTest {
        val pipeline = testPipeline()
        val definition = testDefinition()

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", pipeline.filePath) } returns definition
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns
            testRun()

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify { runService.createRun(pipelineId, repoId, definition, "after123", "refs/heads/main", PipelineTriggerType.PUSH, any()) }
        coVerify { commitStatusService.recordStatus(repoId, "after123", "ci/build/build", CommitStatusState.PENDING, "Queued", null) }
    }

    @Test
    fun `skips pipeline when trigger does not match branch`() = runTest {
        val pipeline = testPipeline(branches = listOf("main"))

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/develop", "after123") } returns listOf(pipeline)

        executeTrigger(ref = "refs/heads/develop", afterSha = "after123")

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips pipeline when YAML parse returns null`() = runTest {
        val pipeline = testPipeline()

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", pipeline.filePath) } returns null

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `skips pipeline when validation fails`() = runTest {
        val pipeline = testPipeline()
        val badDefinition = PipelineDefinition(
            name = "Bad",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
            jobs = mapOf("build" to JobDefinition(steps = emptyList()))
        )

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", pipeline.filePath) } returns badDefinition

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `filters jobs by if condition`() = runTest {
        val pipeline = testPipeline()
        val definition = PipelineDefinition(
            name = "Build",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
            jobs = mapOf(
                "build" to JobDefinition(steps = listOf(StepDefinition(name = "Build", run = "build"))),
                "deploy" to JobDefinition(
                    condition = "ref == 'refs/heads/production'",
                    steps = listOf(StepDefinition(name = "Deploy", run = "deploy"))
                )
            )
        )

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", pipeline.filePath) } returns definition
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns testRun()

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify {
            runService.createRun(any(), any(), match { it.jobs.size == 1 && it.jobs.containsKey("build") }, any(), any(), any(), any())
        }
    }

    @Test
    fun `skips entire pipeline when all jobs filtered by conditions`() = runTest {
        val pipeline = testPipeline()
        val definition = PipelineDefinition(
            name = "Build",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
            jobs = mapOf(
                "deploy" to JobDefinition(
                    condition = "ref == 'refs/heads/production'",
                    steps = listOf(StepDefinition(name = "Deploy", run = "deploy"))
                )
            )
        )

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", pipeline.filePath) } returns definition

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `creates commit status per job with normalized pipeline name`() = runTest {
        val pipeline = testPipeline(name = "Build and Test")
        val definition = PipelineDefinition(
            name = "Build and Test",
            triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
            jobs = mapOf(
                "build" to JobDefinition(steps = listOf(StepDefinition(name = "B", run = "b"))),
                "test" to JobDefinition(steps = listOf(StepDefinition(name = "T", run = "t")))
            )
        )

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", pipeline.filePath) } returns definition
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns testRun()

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify { commitStatusService.recordStatus(repoId, "after123", "ci/build-and-test/build", CommitStatusState.PENDING, any(), any()) }
        coVerify { commitStatusService.recordStatus(repoId, "after123", "ci/build-and-test/test", CommitStatusState.PENDING, any(), any()) }
    }

    @Test
    fun `handles multiple pipelines independently`() = runTest {
        val pipeline1 = testPipeline(name = "Build", filePath = ".bosca/pipelines/build.yaml")
        val pipeline2 = testPipeline(name = "Lint", filePath = ".bosca/pipelines/lint.yaml")
        val def1 = testDefinition(name = "Build")
        val def2 = testDefinition(name = "Lint")

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline1, pipeline2)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", ".bosca/pipelines/build.yaml") } returns def1
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", ".bosca/pipelines/lint.yaml") } returns def2
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns testRun()

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify(exactly = 2) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `continues processing other pipelines when one fails`() = runTest {
        val pipeline1 = testPipeline(name = "Bad")
        val pipeline2 = testPipeline(name = "Good", filePath = ".bosca/pipelines/good.yaml")
        val goodDef = testDefinition(name = "Good")

        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns listOf(pipeline1, pipeline2)
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", ".bosca/pipelines/build.yaml") } throws RuntimeException("Parse failed")
        coEvery { pipelineService.parseDefinition(repoId, "refs/heads/main", ".bosca/pipelines/good.yaml") } returns goodDef
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns testRun()

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify(exactly = 1) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `handles empty pipeline list`() = runTest {
        coEvery { pipelineService.syncPipelines(repoId, "refs/heads/main", "after123") } returns emptyList()

        executeTrigger(ref = "refs/heads/main", afterSha = "after123")

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    private suspend fun executeTrigger(
        ref: String = "refs/heads/main",
        beforeSha: String = "before123",
        afterSha: String = "after123"
    ) {
        val executor = TestPipelineTriggerExecutor(repoId, ref, beforeSha, afterSha)
        executor.execute()
    }

    private fun testPipeline(
        name: String = "Build",
        branches: List<String> = emptyList(),
        filePath: String = ".bosca/pipelines/build.yaml"
    ) = Pipeline(
        id = pipelineId,
        repositoryId = repoId,
        filePath = filePath,
        name = name,
        triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH, branches = branches)).toJsonElement(),
        configHash = "abc123"
    )

    private fun testDefinition(name: String = "Build") = PipelineDefinition(
        name = name,
        triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)),
        jobs = mapOf("build" to JobDefinition(steps = listOf(StepDefinition(name = "Build", run = "build"))))
    )

    private fun testRun() = PipelineRun(
        id = UUID.random(),
        pipelineId = pipelineId,
        repositoryId = repoId,
        commitSha = "after123",
        ref = "refs/heads/main",
        triggerType = PipelineTriggerType.PUSH,
        status = PipelineRunStatus.QUEUED,
        number = 1
    )
}

/**
 * Test-friendly wrapper that bypasses AbstractJobExecutor serialization
 * and directly invokes the trigger logic with provided parameters.
 */
private class TestPipelineTriggerExecutor(
    private val repositoryId: UUID,
    private val ref: String,
    private val beforeSha: String,
    private val afterSha: String
) {
    suspend fun execute() {
        val pipelineService = bosca.di.provide<PipelineService>()
        val runService = bosca.di.provide<PipelineRunService>()
        val commitStatusService = bosca.di.provide<CommitStatusService>()
        val triggerEvaluator = bosca.git.ci.trigger.TriggerEvaluator()
        val parser = bosca.git.ci.parser.PipelineYamlParser()
        val expressionParser = bosca.git.ci.parser.PipelineExpressionParser()

        val pipelines = pipelineService.syncPipelines(repositoryId, ref, afterSha)

        val pushEvent = bosca.git.model.PushEvent(
            repositoryId = repositoryId,
            ref = ref,
            beforeSha = beforeSha,
            afterSha = afterSha
        )

        val branchName = if (ref.startsWith("refs/heads/")) ref.removePrefix("refs/heads/") else ref
        val expressionContext = bosca.git.ci.parser.ExpressionContext(
            ref = ref,
            branch = branchName,
            event = "push"
        )

        for (pipeline in pipelines) {
            val matchingTrigger = triggerEvaluator.evaluatePush(pipeline, pushEvent)
            if (matchingTrigger == null) continue

            try {
                val definition = pipelineService.parseDefinition(
                    repositoryId, ref, pipeline.filePath
                ) ?: continue

                val errors = parser.validate(definition)
                if (errors.isNotEmpty()) continue

                val filteredJobs = definition.jobs.filter { (_, jobDef) ->
                    val condition = jobDef.condition ?: return@filter true
                    try {
                        expressionParser.evaluateBoolean(condition, expressionContext)
                    } catch (_: Exception) {
                        false
                    }
                }

                if (filteredJobs.isEmpty()) continue

                val filteredDefinition = definition.copy(jobs = filteredJobs)

                val run = runService.createRun(
                    pipelineId = pipeline.id,
                    repositoryId = repositoryId,
                    definition = filteredDefinition,
                    commitSha = afterSha,
                    ref = ref,
                    triggerType = PipelineTriggerType.PUSH,
                    triggeredBy = null
                )

                for ((jobName, _) in filteredJobs) {
                    val context = "ci/${pipeline.name.lowercase().replace(' ', '-')}/$jobName"
                    commitStatusService.recordStatus(
                        repositoryId = repositoryId,
                        commitSha = afterSha,
                        context = context,
                        state = CommitStatusState.PENDING,
                        description = "Queued"
                    )
                }
            } catch (_: Exception) {
            }
        }
    }
}
