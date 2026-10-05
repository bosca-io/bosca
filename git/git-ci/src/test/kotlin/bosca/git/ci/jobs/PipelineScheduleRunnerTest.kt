package bosca.git.ci.jobs

import bosca.git.model.JobDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineScheduleJob
import bosca.git.model.PipelineTrigger
import bosca.git.model.PipelineTriggerType
import bosca.git.model.Repository
import bosca.git.model.StepDefinition
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineScheduleService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.scheduler.service.SchedulerService
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PipelineScheduleRunnerTest {

    private val scheduleService = mockk<PipelineScheduleService>(relaxed = true)
    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val browseService = mockk<RepositoryBrowseService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private lateinit var runner: PipelineScheduleRunnerImpl

    private val principalId = UUID.random()
    private val pipeline = Pipeline(
        id = UUID.random(),
        repositoryId = UUID.random(),
        filePath = ".bosca/pipelines/nightly.yaml",
        name = "Nightly Build",
        configHash = "hash",
    )
    private val repository = Repository(
        id = pipeline.repositoryId,
        slug = "repo",
        name = "Repo",
        ownerId = UUID.random(),
        defaultBranch = "main",
        visibility = Visibility.PRIVATE,
    )
    private val schedule = scheduledJob()

    @BeforeTest
    fun setup() {
        runner = PipelineScheduleRunnerImpl(
            scheduleService,
            schedulerService,
            pipelineService,
            repositoryService,
            browseService,
            runService,
            commitStatusService,
            securityService,
            permissionEvaluator,
        )
    }

    @Test
    fun `missing inactive or stale principal snapshot does nothing`() = runTest {
        coEvery { scheduleService.findById(schedule.id) } returns null
        runner.run(schedule.id, pipeline.id, principalId)

        coEvery { scheduleService.findById(schedule.id) } returns schedule.copy(
            principalState = ScheduledJobPrincipalState.NEEDS_PRINCIPAL,
        )
        runner.run(schedule.id, pipeline.id, principalId)

        coEvery { scheduleService.findById(schedule.id) } returns schedule
        runner.run(schedule.id, pipeline.id, UUID.random())

        coVerify(exactly = 0) { securityService.getPrincipalById(any()) }
        coVerify(exactly = 0) { pipelineService.findById(any()) }
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `removed or disabled principal parks before pipeline lookup`() = runTest {
        coEvery { scheduleService.findById(schedule.id) } returns schedule
        coEvery { securityService.getPrincipalById(principalId) } returns null
        runner.run(schedule.id, pipeline.id, principalId)

        coEvery { securityService.getPrincipalById(principalId) } returns Principal(
            id = principalId,
            deletedAt = java.time.OffsetDateTime.now(),
        )
        runner.run(schedule.id, pipeline.id, principalId)

        coVerify(exactly = 2) { schedulerService.parkNeedsPrincipal(schedule.id) }
        coVerify(exactly = 0) { pipelineService.findById(any()) }
    }

    @Test
    fun `wrong scheduler definition or payload does nothing`() = runTest {
        livePrincipal()
        coEvery { scheduleService.findById(schedule.id) } returns schedule.copy(jobName = "other")
        runner.run(schedule.id, pipeline.id, principalId)

        coEvery { scheduleService.findById(schedule.id) } returns schedule.copy(jobParameters = JsonObject(emptyMap()))
        runner.run(schedule.id, pipeline.id, principalId)

        coEvery { scheduleService.findById(schedule.id) } returns schedule
        runner.run(schedule.id, UUID.random(), principalId)

        coVerify(exactly = 0) { pipelineService.findById(any()) }
    }

    @Test
    fun `schedule whose pipeline was removed deletes its scheduler state`() = runTest {
        livePrincipal()
        coEvery { scheduleService.findById(schedule.id) } returns schedule
        coEvery { pipelineService.findById(pipeline.id) } returns null

        runner.run(schedule.id, pipeline.id, principalId)

        coVerify { scheduleService.delete(schedule.id) }
    }

    @Test
    fun `an archived pipeline cannot execute a queued schedule`() = runTest {
        happyEligibility()
        coEvery { pipelineService.findById(pipeline.id) } returns pipeline.copy(deletedAt = OffsetDateTime.now())

        runner.run(schedule.id, pipeline.id, principalId)

        coVerify { scheduleService.delete(schedule.id) }
        coVerify(exactly = 0) { browseService.resolveRef(any(), any()) }
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing repository ref or definition fails loudly`() = runTest {
        happyEligibility()
        coEvery { repositoryService.findById(repository.id) } returns null
        assertFailsWith<NoSuchElementException> { runner.run(schedule.id, pipeline.id, principalId) }

        coEvery { repositoryService.findById(repository.id) } returns repository
        coEvery { browseService.resolveRef(repository.id, "refs/heads/main") } returns null
        assertFailsWith<NoSuchElementException> { runner.run(schedule.id, pipeline.id, principalId) }

        coEvery { browseService.resolveRef(repository.id, "refs/heads/main") } returns "sha"
        coEvery { pipelineService.parseDefinition(repository.id, "sha", pipeline.filePath) } returns null
        assertFailsWith<IllegalStateException> { runner.run(schedule.id, pipeline.id, principalId) }
    }

    @Test
    fun `principal without execute permission parks and never resolves source`() = runTest {
        happyEligibility(executable = false)

        runner.run(schedule.id, pipeline.id, principalId)

        coVerify { schedulerService.parkNeedsPrincipal(schedule.id) }
        coVerify(exactly = 0) { browseService.resolveRef(any(), any()) }
    }

    @Test
    fun `removed cron deletes stale schedule without creating a run`() = runTest {
        happyEligibility()
        sourceDefinition(definition(triggerCron = "0 4 * * *"))

        runner.run(schedule.id, pipeline.id, principalId)

        coVerify { scheduleService.delete(schedule.id) }
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid scheduled definition fails loudly`() = runTest {
        happyEligibility()
        sourceDefinition(definition().copy(jobs = mapOf("bad" to JobDefinition())))

        assertFailsWith<IllegalStateException> { runner.run(schedule.id, pipeline.id, principalId) }
    }

    @Test
    fun `all jobs filtered by schedule condition creates no run`() = runTest {
        happyEligibility()
        sourceDefinition(
            definition().copy(
                jobs = mapOf(
                    "manual-only" to JobDefinition(
                        condition = "event == 'manual'",
                        steps = listOf(StepDefinition(name = "No", run = "false")),
                    )
                ),
            )
        )

        runner.run(schedule.id, pipeline.id, principalId)

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `eligible occurrence creates attributed schedule run and pending statuses`() = runTest {
        happyEligibility()
        sourceDefinition(definition())
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns PipelineRun(
            id = UUID.random(),
            pipelineId = pipeline.id,
            repositoryId = repository.id,
            commitSha = "sha",
            ref = "refs/heads/main",
            triggerType = PipelineTriggerType.SCHEDULE,
            triggeredBy = principalId,
            status = PipelineRunStatus.QUEUED,
            number = 7,
        )

        runner.run(schedule.id, pipeline.id, principalId)

        coVerify {
            runService.createRun(
                pipeline.id,
                repository.id,
                match { it.jobs.keys == setOf("build", "test") },
                "sha",
                "refs/heads/main",
                PipelineTriggerType.SCHEDULE,
                principalId,
            )
        }
        coVerify { commitStatusService.recordStatus(repository.id, "sha", "ci/nightly-build/build", any(), "Queued", null) }
        coVerify { commitStatusService.recordStatus(repository.id, "sha", "ci/nightly-build/test", any(), "Queued", null) }
    }

    private suspend fun livePrincipal() {
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { securityService.getPrincipalGroups(principalId) } returns listOf(
            Group(UUID.random(), "ci-runners", "CI", GroupType.PRINCIPAL),
        )
    }

    private suspend fun happyEligibility(executable: Boolean = true) {
        coEvery { scheduleService.findById(schedule.id) } returns schedule
        livePrincipal()
        coEvery { pipelineService.findById(pipeline.id) } returns pipeline
        coEvery { repositoryService.findById(repository.id) } returns repository
        coEvery { permissionEvaluator.isAllowed(any<AuthenticationContext>(), repository, PermissionAction.EXECUTE) } returns executable
    }

    private suspend fun sourceDefinition(definition: PipelineDefinition) {
        coEvery { browseService.resolveRef(repository.id, "refs/heads/main") } returns "sha"
        coEvery { pipelineService.parseDefinition(repository.id, "sha", pipeline.filePath) } returns definition
    }

    private fun scheduledJob() = ScheduledJob(
        id = UUID.random(),
        name = "Git pipeline schedule: ${pipeline.name}",
        jobName = PipelineScheduleJob.NAME,
        jobParameters = Json.encodeToJsonElement(PipelineScheduleJob.serializer(), PipelineScheduleJob(pipeline.id)),
        cronExpression = "0 3 * * 1",
        enabled = true,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        createdBy = UUID.NIL,
        executionPrincipalId = principalId,
        principalState = ScheduledJobPrincipalState.ACTIVE,
        principalAssignedBy = principalId,
        principalConfirmedBy = principalId,
    )

    private fun definition(triggerCron: String = schedule.cronExpression) = PipelineDefinition(
        name = pipeline.name,
        triggers = listOf(
            PipelineTrigger(PipelineTriggerType.PUSH),
            PipelineTrigger(PipelineTriggerType.SCHEDULE, cron = triggerCron),
        ),
        jobs = mapOf(
            "build" to JobDefinition(steps = listOf(StepDefinition(name = "Build", run = "build"))),
            "test" to JobDefinition(steps = listOf(StepDefinition(name = "Test", run = "test"))),
        ),
    )
}
