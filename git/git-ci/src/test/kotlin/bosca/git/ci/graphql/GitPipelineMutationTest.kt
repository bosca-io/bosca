package bosca.git.ci.graphql

import bosca.di.provides
import bosca.di.annotation.InternalDI
import bosca.git.model.AgentMode
import bosca.git.model.AgentStatus
import bosca.git.model.CommitStatusState
import bosca.git.model.JobDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineScheduleJob
import bosca.git.model.PipelineSecret
import bosca.git.model.PipelineStep
import bosca.git.model.PipelineTrigger
import bosca.git.ci.toJsonElement
import bosca.git.model.PipelineTriggerType
import bosca.git.model.Repository
import bosca.git.model.StepDefinition
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.CommitStatusService
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineScheduleService
import bosca.git.service.PipelineSecretService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.scheduler.service.SchedulerService
import bosca.scheduler.model.ScheduledJob
import bosca.scheduler.model.ScheduledJobPrincipalState
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.Principal
import bosca.pubsub.PubSubService
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class GitPipelineMutationTest {

    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val secretService = mockk<PipelineSecretService>(relaxed = true)
    private val logService = mockk<PipelineLogService>(relaxed = true)
    private val commitStatusService = mockk<CommitStatusService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val browseService = mockk<RepositoryBrowseService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val writeService = mockk<bosca.git.service.RepositoryWriteService>(relaxed = true)
    private val scheduleService = mockk<PipelineScheduleService>(relaxed = true)
    private val schedulerService = mockk<SchedulerService>(relaxed = true)
    private val securityService = mockk<SecurityService>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private lateinit var mutation: GitPipelineMutation

    private val repoId = UUID.random()
    private val pipelineId = UUID.random()

    @BeforeTest
    fun setup() {
        bosca.di.ProviderRegistry.clear()
        provides<PubSubService>(singleton = true) { mockk(relaxed = true) }
        mutation = GitPipelineMutation(
            pipelineService, runService, jobService, agentService,
            secretService, logService, commitStatusService, repositoryService, browseService,
            writeService, permissionEvaluator, scheduleService, schedulerService, securityService,
        )
        coEvery { repositoryService.findById(repoId) } returns testRepo()
        coEvery { jobService.finishIfActive(any(), any(), any()) } returns true
        authenticate(UUID.random())
    }

    @Test
    fun `self assignment activates a pipeline schedule immediately`() = runTest {
        val actorId = UUID.random()
        val schedule = scheduleJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        scheduleFixture(schedule)
        authenticate(actorId)
        eligiblePrincipal(actorId)
        val active = schedule.copy(
            executionPrincipalId = actorId,
            principalState = ScheduledJobPrincipalState.ACTIVE,
            principalAssignedBy = actorId,
            principalConfirmedBy = actorId,
        )
        coEvery {
            schedulerService.assignExecutionPrincipal(schedule.id, actorId, actorId, actorId)
        } returns active

        assertEquals(active, mutation.assignPipelineSchedule(authentication, schedule.id, actorId))
        coVerify { permissionEvaluator.verifyAllowed(authentication, any(), bosca.security.model.PermissionAction.MANAGE) }
    }

    @Test
    fun `assigning another principal requires confirmation unless actor is admin`() = runTest {
        val actorId = UUID.random()
        val targetId = UUID.random()
        val schedule = scheduleJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        scheduleFixture(schedule)
        eligiblePrincipal(targetId)
        authenticate(actorId)
        val pending = schedule.copy(
            executionPrincipalId = targetId,
            principalState = ScheduledJobPrincipalState.PENDING_CONFIRMATION,
            principalAssignedBy = actorId,
        )
        coEvery {
            schedulerService.assignExecutionPrincipal(schedule.id, targetId, actorId, null)
        } returns pending

        assertEquals(pending, mutation.assignPipelineSchedule(authentication, schedule.id, targetId))

        authenticate(actorId, admin = true)
        val active = pending.copy(principalState = ScheduledJobPrincipalState.ACTIVE, principalConfirmedBy = actorId)
        coEvery {
            schedulerService.assignExecutionPrincipal(schedule.id, targetId, actorId, actorId)
        } returns active
        assertEquals(active, mutation.assignPipelineSchedule(authentication, schedule.id, targetId))
    }

    @Test
    fun `assignment rejects missing disabled or unauthorized principals`() = runTest {
        val actorId = UUID.random()
        val targetId = UUID.random()
        val schedule = scheduleJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        scheduleFixture(schedule)
        authenticate(actorId)

        coEvery { securityService.getPrincipalById(targetId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.assignPipelineSchedule(authentication, schedule.id, targetId)
        }

        coEvery { securityService.getPrincipalById(targetId) } returns Principal(
            id = targetId,
            deletedAt = java.time.OffsetDateTime.now(),
        )
        assertFailsWith<IllegalStateException> {
            mutation.assignPipelineSchedule(authentication, schedule.id, targetId)
        }

        coEvery { securityService.getPrincipalById(targetId) } returns Principal(id = targetId)
        coEvery { securityService.getPrincipalGroups(targetId) } returns emptyList()
        coEvery {
            permissionEvaluator.isAllowed(any<AuthenticationContext>(), any<Repository>(), bosca.security.model.PermissionAction.EXECUTE)
        } returns false
        assertFailsWith<SecurityException> {
            mutation.assignPipelineSchedule(authentication, schedule.id, targetId)
        }
    }

    @Test
    fun `target confirms pending assignment and active confirmation is idempotent`() = runTest {
        val targetId = UUID.random()
        val pending = scheduleJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, targetId)
        scheduleFixture(pending)
        authenticate(targetId)
        eligiblePrincipal(targetId)
        val active = pending.copy(
            principalState = ScheduledJobPrincipalState.ACTIVE,
            principalConfirmedBy = targetId,
        )
        coEvery { schedulerService.confirmExecutionPrincipal(pending.id, targetId) } returns active

        assertEquals(active, mutation.confirmPipelineScheduleAssignment(authentication, pending.id))

        coEvery { scheduleService.findById(active.id) } returns active
        assertSame(active, mutation.confirmPipelineScheduleAssignment(authentication, active.id))
        coVerify(exactly = 1) { schedulerService.confirmExecutionPrincipal(pending.id, targetId) }
    }

    @Test
    fun `unrelated principal cannot confirm pending assignment`() = runTest {
        val pending = scheduleJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, UUID.random())
        scheduleFixture(pending)
        authenticate(UUID.random())

        assertFailsWith<SecurityException> {
            mutation.confirmPipelineScheduleAssignment(authentication, pending.id)
        }
        coVerify(exactly = 0) { schedulerService.confirmExecutionPrincipal(any(), any()) }
    }

    @Test
    fun `assigned principal or repository manager can clear assignment`() = runTest {
        val assignedId = UUID.random()
        val schedule = scheduleJob(ScheduledJobPrincipalState.ACTIVE, assignedId)
        scheduleFixture(schedule)
        val parked = schedule.copy(
            enabled = false,
            executionPrincipalId = null,
            principalState = ScheduledJobPrincipalState.NEEDS_PRINCIPAL,
            principalAssignedBy = null,
            principalConfirmedBy = null,
        )
        coEvery { schedulerService.clearExecutionPrincipal(schedule.id) } returns parked

        authenticate(assignedId)
        assertEquals(parked, mutation.clearPipelineScheduleAssignment(authentication, schedule.id))

        authenticate(UUID.random())
        coEvery {
            permissionEvaluator.isAllowed(authentication, any<Repository>(), bosca.security.model.PermissionAction.MANAGE)
        } returns true
        assertEquals(parked, mutation.clearPipelineScheduleAssignment(authentication, schedule.id))

        coEvery {
            permissionEvaluator.isAllowed(authentication, any<Repository>(), bosca.security.model.PermissionAction.MANAGE)
        } returns false
        assertFailsWith<SecurityException> {
            mutation.clearPipelineScheduleAssignment(authentication, schedule.id)
        }
    }

    @Test
    fun `schedule assignment operations require an authenticated actor`() = runTest {
        val targetId = UUID.random()
        val pending = scheduleJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, targetId)
        scheduleFixture(pending)
        every { authentication.principal() } returns null

        assertFailsWith<SecurityException> {
            mutation.assignPipelineSchedule(authentication, pending.id, targetId)
        }
        assertFailsWith<SecurityException> {
            mutation.confirmPipelineScheduleAssignment(authentication, pending.id)
        }
        assertFailsWith<SecurityException> {
            mutation.clearPipelineScheduleAssignment(authentication, pending.id)
        }
    }

    @Test
    fun `scheduler write races fail instead of returning phantom assignments`() = runTest {
        val actorId = UUID.random()
        val pending = scheduleJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, actorId)
        scheduleFixture(pending)
        authenticate(actorId)
        eligiblePrincipal(actorId)
        coEvery { schedulerService.assignExecutionPrincipal(any(), any(), any(), any()) } returns null
        coEvery { schedulerService.confirmExecutionPrincipal(any(), any()) } returns null
        coEvery { schedulerService.clearExecutionPrincipal(any()) } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.assignPipelineSchedule(authentication, pending.id, actorId)
        }
        assertFailsWith<NoSuchElementException> {
            mutation.confirmPipelineScheduleAssignment(authentication, pending.id)
        }
        assertFailsWith<NoSuchElementException> {
            mutation.clearPipelineScheduleAssignment(authentication, pending.id)
        }
    }

    @Test
    fun `confirmation requires a pending assignment with a target`() = runTest {
        val actorId = UUID.random()
        authenticate(actorId)
        val unassigned = scheduleJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        scheduleFixture(unassigned)
        assertFailsWith<IllegalStateException> {
            mutation.confirmPipelineScheduleAssignment(authentication, unassigned.id)
        }

        val missingTarget = scheduleJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION)
        scheduleFixture(missingTarget)
        assertFailsWith<IllegalStateException> {
            mutation.confirmPipelineScheduleAssignment(authentication, missingTarget.id)
        }
    }

    @Test
    fun `administrator can confirm another principal assignment`() = runTest {
        val adminId = UUID.random()
        val targetId = UUID.random()
        val pending = scheduleJob(ScheduledJobPrincipalState.PENDING_CONFIRMATION, targetId)
        val active = pending.copy(
            principalState = ScheduledJobPrincipalState.ACTIVE,
            principalConfirmedBy = adminId,
        )
        scheduleFixture(pending)
        authenticate(adminId, admin = true)
        eligiblePrincipal(targetId)
        coEvery { schedulerService.confirmExecutionPrincipal(pending.id, adminId) } returns active

        assertSame(active, mutation.confirmPipelineScheduleAssignment(authentication, pending.id))
    }

    @Test
    fun `schedule lookup fails closed for missing malformed or detached rows`() = runTest {
        val id = UUID.random()
        coEvery { scheduleService.findById(id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.assignPipelineSchedule(authentication, id, UUID.random())
        }

        val malformed = scheduleJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL).copy(
            jobParameters = JsonObject(emptyMap()),
        )
        coEvery { scheduleService.findById(malformed.id) } returns malformed
        assertFailsWith<IllegalStateException> {
            mutation.assignPipelineSchedule(authentication, malformed.id, UUID.random())
        }

        val detached = scheduleJob(ScheduledJobPrincipalState.NEEDS_PRINCIPAL)
        coEvery { scheduleService.findById(detached.id) } returns detached
        coEvery { pipelineService.findById(pipelineId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.assignPipelineSchedule(authentication, detached.id, UUID.random())
        }

        coEvery { pipelineService.findById(pipelineId) } returns testPipeline()
        coEvery { repositoryService.findById(repoId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.assignPipelineSchedule(authentication, detached.id, UUID.random())
        }
    }

    @Test
    fun `build entry points require execution permission before dispatch`() = runTest {
        val run = testRun()
        val job = testJob().copy(pipelineRunId = run.id)
        coEvery { pipelineService.findById(pipelineId) } returns testPipeline()
        coEvery { runService.findById(run.id) } returns run
        coEvery { jobService.findById(job.id) } returns job
        coEvery {
            permissionEvaluator.verifyAllowed(authentication, any<Repository>(), bosca.security.model.PermissionAction.EXECUTE)
        } throws SecurityException("No execution grant")

        assertFailsWith<SecurityException> { mutation.triggerPipeline(authentication, pipelineId, "main") }
        assertFailsWith<SecurityException> { mutation.rerunPipeline(authentication, run.id) }
        assertFailsWith<SecurityException> { mutation.rerunPipelineJob(authentication, job.id) }
        assertFailsWith<SecurityException> { mutation.rerunFailedJobs(authentication, run.id) }
        assertFailsWith<SecurityException> { mutation.runPipelineJobAnyway(authentication, job.id, null) }
        assertFailsWith<SecurityException> { mutation.approvePipelineJob(authentication, job.id, null) }
        assertFailsWith<SecurityException> { mutation.rejectPipelineJob(authentication, job.id, null) }

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { runService.rerun(any(), any()) }
        coVerify(exactly = 0) { runService.rerunJob(any(), any()) }
        coVerify(exactly = 0) { runService.rerunFailedJobs(any(), any()) }
        coVerify(exactly = 0) { runService.runJobAnyway(any(), any(), any()) }
        coVerify(exactly = 0) { jobService.approve(any(), any(), any()) }
        coVerify(exactly = 0) { jobService.rejectApproval(any(), any(), any()) }
    }

    @Test
    fun `build entry points require an attributable principal`() = runTest {
        val run = testRun()
        val job = testJob().copy(pipelineRunId = run.id)
        coEvery { pipelineService.findById(pipelineId) } returns testPipeline()
        coEvery { runService.findById(run.id) } returns run
        coEvery { jobService.findById(job.id) } returns job
        every { authentication.principal() } returns null

        assertFailsWith<SecurityException> { mutation.triggerPipeline(authentication, pipelineId, "main") }
        assertFailsWith<SecurityException> { mutation.rerunPipeline(authentication, run.id) }
        assertFailsWith<SecurityException> { mutation.rerunPipelineJob(authentication, job.id) }
        assertFailsWith<SecurityException> { mutation.rerunFailedJobs(authentication, run.id) }
        assertFailsWith<SecurityException> { mutation.runPipelineJobAnyway(authentication, job.id, null) }
        assertFailsWith<SecurityException> { mutation.approvePipelineJob(authentication, job.id, null) }

        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { runService.rerun(any(), any()) }
        coVerify(exactly = 0) { runService.rerunJob(any(), any()) }
        coVerify(exactly = 0) { runService.rerunFailedJobs(any(), any()) }
        coVerify(exactly = 0) { runService.runJobAnyway(any(), any(), any()) }
        coVerify(exactly = 0) { jobService.approve(any(), any(), any()) }
    }


    @Test
    fun `triggerPipeline creates run with MANUAL trigger type`() = runTest {
        val pipeline = testPipeline()
        val definition = testDefinition()
        val sha = "0123456789abcdef0123456789abcdef01234567"
        coEvery { pipelineService.findById(pipelineId) } returns pipeline
        coEvery { browseService.resolveRef(repoId, "main") } returns sha
        coEvery { pipelineService.parseDefinition(repoId, sha, pipeline.filePath) } returns definition
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns testRun()

        val result = mutation.triggerPipeline(authentication, pipelineId, "main")

        assertNotNull(result)
        coVerify { runService.createRun(pipelineId, repoId, definition, sha, "main", PipelineTriggerType.MANUAL, any()) }
    }

    @Test
    fun `triggerPipeline records the resolved SHA, never the symbolic ref`() = runTest {
        val pipeline = testPipeline()
        val sha = "89abcdef0123456789abcdef0123456789abcdef"
        coEvery { pipelineService.findById(pipelineId) } returns pipeline
        coEvery { browseService.resolveRef(repoId, "refs/heads/main") } returns sha
        coEvery { pipelineService.parseDefinition(repoId, sha, pipeline.filePath) } returns testDefinition()
        coEvery { runService.createRun(any(), any(), any(), any(), any(), any(), any()) } returns testRun()

        mutation.triggerPipeline(authentication, pipelineId, "refs/heads/main")

        coVerify { runService.createRun(pipelineId, repoId, any(), sha, "refs/heads/main", PipelineTriggerType.MANUAL, any()) }
    }

    @Test
    fun `triggerPipeline throws when the ref does not resolve`() = runTest {
        coEvery { pipelineService.findById(pipelineId) } returns testPipeline()
        coEvery { browseService.resolveRef(repoId, "refs/heads/nope") } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.triggerPipeline(authentication, pipelineId, "refs/heads/nope")
        }
        coVerify(exactly = 0) { runService.createRun(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `triggerPipeline throws when pipeline not found`() = runTest {
        coEvery { pipelineService.findById(any()) } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.triggerPipeline(authentication, UUID.random(), "main")
        }
    }

    @Test
    fun `triggerPipeline throws when definition parse fails`() = runTest {
        val pipeline = testPipeline()
        coEvery { pipelineService.findById(pipelineId) } returns pipeline
        coEvery { pipelineService.parseDefinition(any(), any(), any()) } returns null

        assertFailsWith<IllegalStateException> {
            mutation.triggerPipeline(authentication, pipelineId, "main")
        }
    }

    @Test
    fun `cancelPipelineRun delegates to run service`() = runTest {
        val run = testRun()
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()

        mutation.cancelPipelineRun(authentication, run.id)

        coVerify { runService.cancelRun(run.id) }
    }

    @Test
    fun `deletePipelineRun verifies manage permission and delegates to run service`() = runTest {
        val run = testRun().copy(status = PipelineRunStatus.FAILURE)
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()
        coEvery { runService.delete(run.id) } returns run

        assertTrue(mutation.deletePipelineRun(authentication, run.id))

        coVerify {
            permissionEvaluator.verifyAllowed(
                authentication,
                any<Repository>(),
                bosca.security.model.PermissionAction.MANAGE,
            )
        }
        coVerify(exactly = 1) { runService.delete(run.id) }
    }

    @Test
    fun `cancelPipelineJob verifies edit permission and delegates to run service`() = runTest {
        val job = testJob(status = PipelineRunStatus.RUNNING)
        val run = testRun(id = job.pipelineRunId)
        val cancelled = job.copy(status = PipelineRunStatus.CANCELLED)
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()
        coEvery { runService.cancelJob(job.id) } returns cancelled

        assertEquals(cancelled, mutation.cancelPipelineJob(authentication, job.id))

        coVerify {
            permissionEvaluator.verifyAllowed(
                authentication,
                any<Repository>(),
                bosca.security.model.PermissionAction.EDIT,
            )
        }
        coVerify(exactly = 1) { runService.cancelJob(job.id) }
    }

    @Test
    fun `rerunPipeline delegates to run service`() = runTest {
        val run = testRun()
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()
        coEvery { runService.rerun(run.id, any()) } returns testRun()

        mutation.rerunPipeline(authentication, run.id)

        coVerify { runService.rerun(run.id, any()) }
    }

    @Test
    fun `rerunPipelineJob verifies execution permission and delegates to run service`() = runTest {
        val actorId = UUID.random()
        val job = testJob(status = PipelineRunStatus.FAILURE)
        val run = testRun(id = job.pipelineRunId)
        val reset = job.copy(status = PipelineRunStatus.QUEUED, attempt = 2)
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = actorId), emptyList())
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()
        coEvery { runService.rerunJob(job.id, actorId) } returns reset

        assertEquals(reset, mutation.rerunPipelineJob(authentication, job.id))

        coVerify {
            permissionEvaluator.verifyAllowed(
                authentication,
                any<Repository>(),
                bosca.security.model.PermissionAction.EXECUTE,
            )
        }
        coVerify(exactly = 1) { runService.rerunJob(job.id, actorId) }
    }

    @Test
    fun `runPipelineJobAnyway verifies execution permission and attributes the override`() = runTest {
        val actorId = UUID.random()
        val job = testJob(status = PipelineRunStatus.QUEUED).copy(
            pipelineRequirements = Json.parseToJsonElement(
                """[{"repository":"bosca","pipeline":"build"}]"""
            ),
        )
        val run = testRun(id = job.pipelineRunId)
        val bypassed = job.copy(
            requirementsSatisfiedAt = bosca.serialization.OffsetDateTime.now(),
            requirementsBypassedAt = bosca.serialization.OffsetDateTime.now(),
            requirementsBypassedBy = actorId,
        )
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = actorId), emptyList())
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()
        coEvery { runService.runJobAnyway(job.id, actorId, "release blocked") } returns bypassed

        assertEquals(
            bypassed,
            mutation.runPipelineJobAnyway(authentication, job.id, "release blocked"),
        )

        coVerify {
            permissionEvaluator.verifyAllowed(
                authentication,
                any<Repository>(),
                bosca.security.model.PermissionAction.EXECUTE,
            )
        }
        coVerify(exactly = 1) {
            runService.runJobAnyway(job.id, actorId, "release blocked")
        }
    }

    @Test
    fun `registerAgent returns agent and token`() = runTest {
        val agent = testAgent()
        coEvery { agentService.register("test", listOf("linux"), AgentMode.RUNNER, any()) } returns (agent to "bga_token")

        val result = mutation.registerAgent(authentication, "test", listOf("linux"), AgentMode.RUNNER)

        assertEquals(agent.id, result.agent.id)
        assertEquals("bga_token", result.token)
    }

    @Test
    fun `registerEphemeralAgent returns agent and token`() = runTest {
        val jobId = UUID.random()
        val parentId = UUID.random()
        val agent = testAgent(ephemeral = true)
        coEvery { agentService.registerEphemeral(jobId, "eph", listOf("linux"), parentId, 30, any()) } returns (agent to "bga_eph")

        val result = mutation.registerEphemeralAgent(authentication, jobId, "eph", listOf("linux"), parentId, 30)

        assertTrue(result.agent.ephemeral)
        assertEquals("bga_eph", result.token)
    }

    @Test
    fun `orchestrator token registers only its own ephemeral agents`() = runTest {
        val credentialId = 41L
        val parentId = UUID.random()
        val jobId = UUID.random()
        val child = testAgent(ephemeral = true, parentAgentId = parentId)
        authenticateScoped(credentialId)
        coEvery { agentService.findById(parentId) } returns testAgent(
            id = parentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId,
        )
        coEvery {
            agentService.registerEphemeral(jobId, "eph", listOf("linux"), parentId, 30, any())
        } returns (child to "bga_eph")

        assertSame(
            child,
            mutation.registerEphemeralAgent(
                authentication,
                jobId,
                "eph",
                listOf("linux"),
                parentId,
                30,
            ).agent,
        )

        coEvery { agentService.findById(parentId) } returns testAgent(
            id = parentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId + 1,
        )
        assertFailsWith<SecurityException> {
            mutation.registerEphemeralAgent(
                authentication,
                jobId,
                "eph",
                listOf("linux"),
                parentId,
                30,
            )
        }
    }

    @Test
    fun `deregisterAgent delegates to agent service`() = runTest {
        val id = UUID.random()
        val result = mutation.deregisterAgent(authentication, id)
        assertTrue(result)
        coVerify { agentService.deregister(id) }
    }

    @Test
    fun `agent token deregisters itself or its owned child but not an unrelated child`() = runTest {
        val credentialId = 44L
        val selfId = UUID.random()
        val childId = UUID.random()
        val unrelatedChildId = UUID.random()
        val parentId = UUID.random()
        val unrelatedParentId = UUID.random()
        authenticateScoped(credentialId)
        coEvery { agentService.findById(selfId) } returns testAgent(
            id = selfId,
            apiTokenCredentialId = credentialId,
        )
        coEvery { agentService.findById(childId) } returns testAgent(
            id = childId,
            ephemeral = true,
            parentAgentId = parentId,
        )
        coEvery { agentService.findById(parentId) } returns testAgent(
            id = parentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId,
        )
        coEvery { agentService.findById(unrelatedChildId) } returns testAgent(
            id = unrelatedChildId,
            ephemeral = true,
            parentAgentId = unrelatedParentId,
        )
        coEvery { agentService.findById(unrelatedParentId) } returns testAgent(
            id = unrelatedParentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId + 1,
        )

        assertTrue(mutation.deregisterAgent(authentication, selfId))
        assertTrue(mutation.deregisterAgent(authentication, childId))
        assertFailsWith<SecurityException> {
            mutation.deregisterAgent(authentication, unrelatedChildId)
        }

        coVerify { agentService.deregister(selfId) }
        coVerify { agentService.deregister(childId) }
        coVerify(exactly = 0) { agentService.deregister(unrelatedChildId) }
    }

    @Test
    fun `setAgentInstanceId delegates to agent service`() = runTest {
        val id = UUID.random()
        coEvery { agentService.findById(id) } returns testAgent(id = id, ephemeral = true)

        val result = mutation.setAgentInstanceId(authentication, id, "12345678")

        assertTrue(result)
        coVerify { agentService.setInstanceId(id, "12345678") }
    }

    @Test
    fun `setAgentInstanceId throws when agent not found`() = runTest {
        val id = UUID.random()
        coEvery { agentService.findById(id) } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.setAgentInstanceId(authentication, id, "12345678")
        }
    }

    @Test
    fun `orchestrator token sets instance id only on its own child`() = runTest {
        val credentialId = 42L
        val parentId = UUID.random()
        val childId = UUID.random()
        authenticateScoped(credentialId)
        coEvery { agentService.findById(childId) } returns testAgent(
            id = childId,
            ephemeral = true,
            parentAgentId = parentId,
        )
        coEvery { agentService.findById(parentId) } returns testAgent(
            id = parentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId,
        )

        assertTrue(mutation.setAgentInstanceId(authentication, childId, "vm-1"))
        coVerify { agentService.setInstanceId(childId, "vm-1") }

        coEvery { agentService.findById(childId) } returns testAgent(
            id = childId,
            ephemeral = true,
            parentAgentId = null,
        )
        assertFailsWith<SecurityException> {
            mutation.setAgentInstanceId(authentication, childId, "vm-2")
        }
    }

    @Test
    fun `claimJob returns job when available`() = runTest {
        val agentId = UUID.random()
        val job = testJob()
        coEvery { jobService.claimJob(agentId, listOf("linux")) } returns job

        val result = mutation.claimJob(authentication, agentId, listOf("linux"))

        assertNotNull(result)
        coVerify { agentService.updateStatus(agentId, AgentStatus.BUSY) }
    }

    @Test
    fun `claimJob returns null and resets agent to ONLINE when no jobs`() = runTest {
        val agentId = UUID.random()
        coEvery { jobService.claimJob(agentId, any()) } returns null

        val result = mutation.claimJob(authentication, agentId, listOf("linux"))

        assertNull(result)
        coVerify { agentService.updateStatus(agentId, AgentStatus.ONLINE) }
    }

    @Test
    fun `claimJob with job id lets an ephemeral agent claim only its assigned job`() = runTest {
        val parentAgentId = UUID.random()
        val agentId = UUID.random()
        val job = testJob()
        val agent = testAgent(
            id = agentId,
            ephemeral = true,
        ).copy(jobId = job.id, parentAgentId = parentAgentId)
        coEvery { agentService.findById(agentId) } returns agent
        coEvery { jobService.findCurrentByAgent(agentId) } returns null
        coEvery { jobService.claimJobById(agentId, job.id, parentAgentId) } returns job

        val result = mutation.claimJob(authentication, agentId, emptyList(), job.id)

        assertEquals(job.id, result?.id)
        coVerify { agentService.updateStatus(agentId, AgentStatus.BUSY) }
    }

    @Test
    fun `claimJob with job id rejects a different job for an ephemeral agent`() = runTest {
        val agentId = UUID.random()
        val assignedJobId = UUID.random()
        val requestedJobId = UUID.random()
        val agent = testAgent(id = agentId, ephemeral = true).copy(jobId = assignedJobId)
        coEvery { agentService.findById(agentId) } returns agent

        assertFailsWith<SecurityException> {
            mutation.claimJob(authentication, agentId, emptyList(), requestedJobId)
        }
        coVerify(exactly = 0) { jobService.claimJobById(any(), any(), any()) }
    }

    @Test
    fun `claimJob with job id rejects a persistent agent`() = runTest {
        val agentId = UUID.random()
        val jobId = UUID.random()
        coEvery { agentService.findById(agentId) } returns testAgent(id = agentId, ephemeral = false)

        assertFailsWith<SecurityException> {
            mutation.claimJob(authentication, agentId, emptyList(), jobId)
        }
        coVerify(exactly = 0) { jobService.claimJobById(any(), any(), any()) }
    }

    @Test
    fun `claimJob with job id rejects an agent retired with its previous attempt`() = runTest {
        val agentId = UUID.random()
        val jobId = UUID.random()
        coEvery { agentService.findById(agentId) } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.claimJob(authentication, agentId, emptyList(), jobId)
        }

        coVerify(exactly = 0) { jobService.claimJobById(any(), any(), any()) }
    }

    @Test
    fun `targeted claim resets an idle agent and finalizes a different abandoned job`() = runTest {
        val parentAgentId = UUID.random()
        val agentId = UUID.random()
        val jobId = UUID.random()
        val abandoned = testJob(agentId = agentId, status = PipelineRunStatus.RUNNING)
        coEvery { agentService.findById(agentId) } returns testAgent(
            id = agentId,
            ephemeral = true,
            parentAgentId = parentAgentId,
        ).copy(jobId = jobId)
        coEvery { jobService.findCurrentByAgent(agentId) } returns abandoned
        coEvery { jobService.claimJobById(agentId, jobId, parentAgentId) } returns null
        coEvery { jobService.findById(abandoned.id) } returns abandoned
        coEvery {
            jobService.finishIfActive(
                abandoned.id,
                PipelineRunStatus.FAILURE,
                "Agent abandoned job before claiming another job",
            )
        } returns true

        assertNull(mutation.claimJob(authentication, agentId, emptyList(), jobId))

        coVerify { agentService.updateStatus(agentId, AgentStatus.ONLINE) }
        coVerify {
            jobService.finishIfActive(
                abandoned.id,
                PipelineRunStatus.FAILURE,
                "Agent abandoned job before claiming another job",
            )
        }
    }

    @Test
    fun `ordinary claim finalizes a different abandoned job but not a redelivery`() = runTest {
        val agentId = UUID.random()
        val abandoned = testJob(agentId = agentId, status = PipelineRunStatus.RUNNING)
        val replacement = testJob(agentId = agentId)
        coEvery { jobService.findCurrentByAgent(agentId) } returns abandoned
        coEvery { jobService.findById(abandoned.id) } returns abandoned
        coEvery { jobService.claimJob(agentId, listOf("linux")) } returns replacement
        coEvery {
            jobService.finishIfActive(
                abandoned.id,
                PipelineRunStatus.FAILURE,
                "Agent abandoned job before claiming another job",
            )
        } returns true

        assertEquals(replacement, mutation.claimJob(authentication, agentId, listOf("linux")))
        coVerify {
            jobService.finishIfActive(
                abandoned.id,
                PipelineRunStatus.FAILURE,
                "Agent abandoned job before claiming another job",
            )
        }

        coEvery { jobService.claimJob(agentId, listOf("linux")) } returns abandoned
        mutation.claimJob(authentication, agentId, listOf("linux"))
        coVerify(exactly = 1) {
            jobService.finishIfActive(
                abandoned.id,
                PipelineRunStatus.FAILURE,
                "Agent abandoned job before claiming another job",
            )
        }
    }

    @Test
    fun `updateJobStatus sets agent back to ONLINE for persistent agents`() = runTest {
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.SUCCESS)
        val run = testRun(id = job.pipelineRunId)
        val agent = testAgent(id = agentId, ephemeral = false)

        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(job)
        coEvery { agentService.findById(agentId) } returns agent

        mutation.updateJobStatus(authentication, job.id, PipelineRunStatus.SUCCESS, null)

        coVerify { agentService.updateStatus(agentId, AgentStatus.ONLINE) }
    }

    @Test
    fun `updateJobStatus removes ephemeral agents`() = runTest {
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.SUCCESS)
        val run = testRun(id = job.pipelineRunId)
        val agent = testAgent(id = agentId, ephemeral = true)

        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(job)
        coEvery { agentService.findById(agentId) } returns agent

        mutation.updateJobStatus(authentication, job.id, PipelineRunStatus.SUCCESS, null)

        coVerify { agentService.deregister(agentId) }
    }

    @Test
    fun `updateJobStatus passes the failure summary through`() = runTest {
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.FAILURE)
        val run = testRun(id = job.pipelineRunId)
        val agent = testAgent(id = agentId, ephemeral = false)

        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(job)
        coEvery { agentService.findById(agentId) } returns agent

        mutation.updateJobStatus(authentication, job.id, PipelineRunStatus.FAILURE, "Insufficient disk space")

        coVerify { jobService.finishIfActive(job.id, PipelineRunStatus.FAILURE, "Insufficient disk space") }
    }

    @Test
    fun `updateJobStatus truncates an oversized failure summary`() = runTest {
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.FAILURE)
        val run = testRun(id = job.pipelineRunId)
        val agent = testAgent(id = agentId, ephemeral = false)

        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns run
        coEvery { jobService.findByRun(run.id) } returns listOf(job)
        coEvery { agentService.findById(agentId) } returns agent

        val oversized = "x".repeat(GitPipelineMutation.MAX_ERROR_MESSAGE_LENGTH + 100)
        mutation.updateJobStatus(authentication, job.id, PipelineRunStatus.FAILURE, oversized)

        coVerify {
            jobService.finishIfActive(
                job.id, PipelineRunStatus.FAILURE,
                oversized.take(GitPipelineMutation.MAX_ERROR_MESSAGE_LENGTH)
            )
        }
    }

    @Test
    fun `job report losing the terminal race does not finalize twice`() = runTest {
        val agentId = UUID.random()
        val current = testJob(
            agentId = agentId,
            status = PipelineRunStatus.RUNNING,
        )
        val settled = current.copy(status = PipelineRunStatus.FAILURE)
        coEvery { jobService.findById(current.id) } returns current andThen settled
        coEvery {
            jobService.finishIfActive(current.id, PipelineRunStatus.SUCCESS, null)
        } returns false

        assertEquals(
            PipelineRunStatus.FAILURE,
            mutation.updateJobStatus(
                authentication,
                current.id,
                PipelineRunStatus.SUCCESS,
                null,
            ).status,
        )

        coVerify(exactly = 1) {
            jobService.finishIfActive(current.id, PipelineRunStatus.SUCCESS, null)
        }
        coVerify(exactly = 0) { jobService.updateStatus(current.id, any(), any()) }
        coVerify(exactly = 0) { agentService.findById(any()) }
    }

    @Test
    fun `job status update fails closed when the job disappears`() = runTest {
        val jobId = UUID.random()
        coEvery { jobService.findById(jobId) } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.updateJobStatus(
                authentication,
                jobId,
                PipelineRunStatus.SUCCESS,
                null,
            )
        }
    }

    @Test
    fun `job status update fails when the updated row disappears`() = runTest {
        val job = testJob(status = PipelineRunStatus.RUNNING)
        coEvery { jobService.findById(job.id) } returns job andThen null

        assertFailsWith<NoSuchElementException> {
            mutation.updateJobStatus(
                authentication,
                job.id,
                PipelineRunStatus.RUNNING,
                null,
            )
        }
    }

    @Test
    fun `updateStepStatus delegates to job service`() = runTest {
        val stepId = UUID.random()
        val job = testJob()
        coEvery { jobService.findStepById(stepId) } returns PipelineStep(
            id = stepId,
            pipelineJobId = job.id,
            name = "build",
            ordinal = 0,
        )
        coEvery { jobService.findById(job.id) } returns job
        mutation.updateStepStatus(authentication, stepId, PipelineRunStatus.SUCCESS, 0, null)
        coVerify { jobService.updateStepStatus(stepId, PipelineRunStatus.SUCCESS, 0, null) }
    }

    @Test
    fun `updateStepStatus passes the failure summary through`() = runTest {
        val stepId = UUID.random()
        val job = testJob()
        coEvery { jobService.findStepById(stepId) } returns PipelineStep(
            id = stepId,
            pipelineJobId = job.id,
            name = "build",
            ordinal = 0,
        )
        coEvery { jobService.findById(job.id) } returns job
        mutation.updateStepStatus(authentication, stepId, PipelineRunStatus.FAILURE, 1, "BUILD FAILED")
        coVerify { jobService.updateStepStatus(stepId, PipelineRunStatus.FAILURE, 1, "BUILD FAILED") }
    }

    @Test
    fun `updateStepStatus truncates an oversized failure summary`() = runTest {
        val stepId = UUID.random()
        val job = testJob()
        coEvery { jobService.findStepById(stepId) } returns PipelineStep(
            id = stepId,
            pipelineJobId = job.id,
            name = "build",
            ordinal = 0,
        )
        coEvery { jobService.findById(job.id) } returns job
        val oversized = "x".repeat(GitPipelineMutation.MAX_ERROR_MESSAGE_LENGTH + 100)
        mutation.updateStepStatus(authentication, stepId, PipelineRunStatus.FAILURE, 1, oversized)
        coVerify {
            jobService.updateStepStatus(
                stepId, PipelineRunStatus.FAILURE, 1,
                oversized.take(GitPipelineMutation.MAX_ERROR_MESSAGE_LENGTH)
            )
        }
    }

    @Test
    fun `step status update rejects missing step and job records`() = runTest {
        val missingStepId = UUID.random()
        coEvery { jobService.findStepById(missingStepId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.updateStepStatus(
                authentication,
                missingStepId,
                PipelineRunStatus.SUCCESS,
                0,
                null,
            )
        }

        val stepId = UUID.random()
        val jobId = UUID.random()
        coEvery { jobService.findStepById(stepId) } returns PipelineStep(
            id = stepId,
            pipelineJobId = jobId,
            name = "build",
            ordinal = 0,
        )
        coEvery { jobService.findById(jobId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.updateStepStatus(
                authentication,
                stepId,
                PipelineRunStatus.SUCCESS,
                0,
                null,
            )
        }
    }

    @Test
    fun `reportCommitStatus delegates to commit status service`() = runTest {
        mutation.reportCommitStatus(authentication, repoId, "abc", "ci/build", CommitStatusState.SUCCESS, "Passed", null)
        coVerify { commitStatusService.recordStatus(repoId, "abc", "ci/build", CommitStatusState.SUCCESS, "Passed", null) }
    }

    @Test
    fun `scoped agent reports status only for its assigned job commit`() = runTest {
        val credentialId = 42L
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.RUNNING)
        val run = testRun(id = job.pipelineRunId)
        val agent = testAgent(
            id = agentId,
            ephemeral = true,
            apiTokenCredentialId = credentialId,
        )
        authenticateScoped(credentialId)
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { agentService.findById(agentId) } returns agent

        mutation.reportCommitStatus(
            authentication,
            repoId,
            run.commitSha,
            "ci/build",
            CommitStatusState.SUCCESS,
            "Passed",
            null,
            job.id,
        )

        coVerify {
            commitStatusService.recordStatus(
                repoId, run.commitSha, "ci/build", CommitStatusState.SUCCESS, "Passed", null
            )
        }
    }

    @Test
    fun `scoped commit status validates job run target and credential`() = runTest {
        val credentialId = 52L
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.RUNNING)
        val run = testRun(id = job.pipelineRunId)
        authenticateScoped(credentialId)
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { agentService.findById(agentId) } returns testAgent(
            id = agentId,
            apiTokenCredentialId = credentialId,
        )

        assertFailsWith<SecurityException> {
            mutation.reportCommitStatus(
                authentication,
                repoId,
                run.commitSha,
                "ci/build",
                CommitStatusState.SUCCESS,
                null,
                null,
            )
        }

        coEvery { jobService.findById(job.id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.reportCommitStatus(
                authentication,
                repoId,
                run.commitSha,
                "ci/build",
                CommitStatusState.SUCCESS,
                null,
                null,
                job.id,
            )
        }

        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.reportCommitStatus(
                authentication,
                repoId,
                run.commitSha,
                "ci/build",
                CommitStatusState.SUCCESS,
                null,
                null,
                job.id,
            )
        }

        coEvery { runService.findById(run.id) } returns run
        listOf(
            Triple(UUID.random(), run.commitSha, "ci/build"),
            Triple(repoId, "different-sha", "ci/build"),
            Triple(repoId, run.commitSha, "ci/other"),
        ).forEach { (targetRepository, targetCommit, targetContext) ->
            coEvery { repositoryService.findById(targetRepository) } returns testRepo()
            assertFailsWith<SecurityException> {
                mutation.reportCommitStatus(
                    authentication,
                    targetRepository,
                    targetCommit,
                    targetContext,
                    CommitStatusState.SUCCESS,
                    null,
                    null,
                    job.id,
                )
            }
        }

        coEvery { agentService.findById(agentId) } returns testAgent(
            id = agentId,
            apiTokenCredentialId = credentialId + 1,
        )
        assertFailsWith<SecurityException> {
            mutation.reportCommitStatus(
                authentication,
                repoId,
                run.commitSha,
                "ci/build",
                CommitStatusState.SUCCESS,
                null,
                null,
                job.id,
            )
        }
    }

    @Test
    fun `pipeline logs reject a run outside the assigned job`() = runTest {
        val job = testJob(status = PipelineRunStatus.RUNNING)
        val differentRun = testRun()
        val step = PipelineStep(
            pipelineJobId = job.id,
            name = "build",
            ordinal = 0,
        )
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(differentRun.id) } returns differentRun
        coEvery { jobService.findStepById(step.id) } returns step

        assertFailsWith<SecurityException> {
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                differentRun.id,
                job.id,
                step.id,
                emptyList(),
            )
        }
        coVerify(exactly = 0) { logService.appendLog(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pipeline logs validate every ownership edge and map streams`() = runTest {
        val agentId = UUID.random()
        val job = testJob(agentId = agentId, status = PipelineRunStatus.RUNNING)
        val run = testRun(id = job.pipelineRunId)
        val step = PipelineStep(
            pipelineJobId = job.id,
            name = "build",
            ordinal = 0,
        )
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { jobService.findStepById(step.id) } returns step
        coEvery { agentService.findById(agentId) } returns testAgent(id = agentId)

        assertTrue(
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                run.id,
                job.id,
                step.id,
                listOf(
                    LogLineInput(1, "now", "out", "stdout"),
                    LogLineInput(2, "now", "err", "STDERR"),
                    LogLineInput(3, "now", "default", null),
                ),
            )
        )
        coVerify {
            logService.appendLog(
                repoId,
                run.id,
                job.id,
                step.id,
                match {
                    it.map(bosca.git.service.LogLine::stream) == listOf(
                        bosca.git.service.LogStream.STDOUT,
                        bosca.git.service.LogStream.STDERR,
                        bosca.git.service.LogStream.STDOUT,
                    )
                },
            )
        }

        coEvery { repositoryService.findById(repoId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                run.id,
                job.id,
                step.id,
                emptyList(),
            )
        }
        coEvery { repositoryService.findById(repoId) } returns testRepo()

        coEvery { runService.findById(run.id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                run.id,
                job.id,
                step.id,
                emptyList(),
            )
        }
        coEvery { runService.findById(run.id) } returns run

        coEvery { jobService.findStepById(step.id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                run.id,
                job.id,
                step.id,
                emptyList(),
            )
        }
    }

    @Test
    fun `agentHeartbeat delegates to agent service`() = runTest {
        val agentId = UUID.random()
        mutation.agentHeartbeat(authentication, agentId)
        coVerify { agentService.heartbeat(agentId) }
    }

    @Test
    fun `setPipelineSecret verifies MANAGE permission`() = runTest {
        coEvery { secretService.setSecret(repoId, "KEY", "val", "production") } returns
            PipelineSecret(repositoryId = repoId, name = "KEY", encryptedValue = "enc", environmentKey = "production")

        mutation.setPipelineSecret(authentication, repoId, "KEY", "val", "production")

        coVerify { permissionEvaluator.verifyAllowed(authentication, any(), any()) }
        coVerify { secretService.setSecret(repoId, "KEY", "val", "production") }
    }

    @Test
    fun `deletePipelineSecret verifies MANAGE permission`() = runTest {
        mutation.deletePipelineSecret(authentication, repoId, "KEY")

        coVerify { permissionEvaluator.verifyAllowed(authentication, any(), any()) }
        coVerify { secretService.deleteSecret(repoId, "KEY") }
    }

    @Test
    fun `configureOrchestrator rejects non-orchestrator agents`() = runTest {
        val agent = testAgent(mode = AgentMode.RUNNER)
        coEvery { agentService.findById(agent.id) } returns agent

        assertFailsWith<IllegalArgumentException> {
            mutation.configureOrchestrator(authentication, agent.id, testOrchestratorInput())
        }
    }

    @Test
    fun `orchestrator token reads only its own configuration`() = runTest {
        val credentialId = 43L
        val agentId = UUID.random()
        val configuration = bosca.git.model.OrchestratorConfig(
            provider = "digitalocean",
            credentials = bosca.git.model.ProviderCredentials(),
            defaults = bosca.git.model.VmDefaults(
                region = "nyc3",
                size = "s-2vcpu-4gb",
                image = "ubuntu-24-04-x64",
            ),
        )
        authenticateScoped(credentialId)
        coEvery { agentService.findById(agentId) } returns testAgent(
            id = agentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId,
            providerConfig = Json.encodeToString(
                bosca.git.model.OrchestratorConfig.serializer(),
                configuration,
            ),
        )

        assertEquals(configuration, mutation.getOrchestratorConfig(authentication, agentId))

        coEvery { agentService.findById(agentId) } returns testAgent(
            id = agentId,
            mode = AgentMode.ORCHESTRATOR,
            apiTokenCredentialId = credentialId + 1,
        )
        assertFailsWith<SecurityException> {
            mutation.getOrchestratorConfig(authentication, agentId)
        }
    }

    @Test
    fun `interactive orchestrator config lookup handles absent agent and config`() = runTest {
        val agentId = UUID.random()
        coEvery { agentService.findById(agentId) } returns testAgent(
            id = agentId,
            mode = AgentMode.ORCHESTRATOR,
            providerConfig = null,
        )

        assertNull(mutation.getOrchestratorConfig(authentication, agentId))
        coVerify {
            permissionEvaluator.verifyAllowed(
                authentication,
                bosca.security.model.PermissionAction.MANAGE,
            )
        }

        coEvery { agentService.findById(agentId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.getOrchestratorConfig(authentication, agentId)
        }
    }

    @Test
    fun `pipeline logs reject missing job and an unassigned scoped job`() = runTest {
        val jobId = UUID.random()
        val stepId = UUID.random()
        coEvery { jobService.findById(jobId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                UUID.random(),
                jobId,
                stepId,
                emptyList(),
            )
        }

        authenticateScoped(81L)
        coEvery { jobService.findById(jobId) } returns testJob(
            agentId = null,
            status = PipelineRunStatus.RUNNING,
        ).copy(id = jobId)
        assertFailsWith<SecurityException> {
            mutation.appendPipelineLogs(
                authentication,
                repoId,
                UUID.random(),
                jobId,
                stepId,
                emptyList(),
            )
        }
    }

    @Test
    fun `job secret resolution requires a running job and maps resolved values`() = runTest {
        val job = testJob(status = PipelineRunStatus.RUNNING)
        val run = testRun(id = job.pipelineRunId)
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns testRepo()
        coEvery { secretService.resolveJobSecrets(job, run) } returns mapOf(
            "TOKEN" to "secret",
        )

        assertEquals(
            listOf(DecryptedSecret("TOKEN", "secret")),
            mutation.resolveJobSecrets(authentication, job.id),
        )

        coEvery { jobService.findById(job.id) } returns job.copy(
            status = PipelineRunStatus.QUEUED,
        )
        assertFailsWith<IllegalStateException> {
            mutation.resolveJobSecrets(authentication, job.id)
        }

        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(run.id) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.resolveJobSecrets(authentication, job.id)
        }
    }

    @Test
    fun `scoped credentials fail closed when their agent records disappear`() = runTest {
        val credentialId = 82L
        val agentId = UUID.random()
        authenticateScoped(credentialId)
        coEvery { agentService.findById(agentId) } returns null

        assertFailsWith<NoSuchElementException> {
            mutation.agentHeartbeat(authentication, agentId)
        }

        val childId = UUID.random()
        val parentId = UUID.random()
        coEvery { agentService.findById(childId) } returns testAgent(
            id = childId,
            ephemeral = true,
            parentAgentId = parentId,
        )
        coEvery { agentService.findById(parentId) } returns null
        assertFailsWith<NoSuchElementException> {
            mutation.deregisterAgent(authentication, childId)
        }
    }

    // ─── createReleaseTag (uses: tag) ────────────────────────────────

    private fun releaseTagFixture(
        triggeredBy: UUID? = UUID.random(),
        jobStatus: PipelineRunStatus = PipelineRunStatus.RUNNING,
        releaseVersion: String? = "6.2.0",
        releaseId: String? = UUID.random().toString(),
    ): Triple<PipelineJob, PipelineRun, Repository> {
        val job = testJob(status = jobStatus)
        val run = testRun(id = job.pipelineRunId).copy(
            triggeredBy = triggeredBy,
            parameters = kotlinx.serialization.json.buildJsonObject {
                releaseVersion?.let {
                    put("release.version", kotlinx.serialization.json.JsonPrimitive(it))
                }
                releaseId?.let {
                    put("release.id", kotlinx.serialization.json.JsonPrimitive(it))
                }
            },
        )
        val repo = testRepo()
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns run
        coEvery { repositoryService.findById(repoId) } returns repo
        return Triple(job, run, repo)
    }

    @Test
    fun `createReleaseTag tags a sibling repository with the run initiator and the release version`() = runTest {
        val (job, run, repo) = releaseTagFixture()
        val sibling = Repository(
            id = UUID.random(), slug = "member-repo", name = "Member",
            ownerId = repo.ownerId, visibility = Visibility.PRIVATE,
        )
        coEvery { repositoryService.findByOwner(repo.ownerId) } returns listOf(repo, sibling)
        val input = io.mockk.slot<bosca.git.service.CreateTagInput>()
        coEvery { writeService.createTag(capture(input)) } answers {
            bosca.git.service.CreateTagResult(tag = "6.2.0", ref = "refs/tags/6.2.0", commitSha = "sha", tagSha = "tag")
        }

        val tagged = mutation.createReleaseTag(authentication, job.id, "member-repo", null, null)

        assertEquals("6.2.0", tagged.tag)
        assertEquals(sibling.id, input.captured.repositoryId)
        assertEquals("6.2.0", input.captured.tag)
        assertEquals("refs/heads/${sibling.defaultBranch}", input.captured.targetRef)
        assertEquals(run.triggeredBy, input.captured.pusherPrincipalId)
    }

    @Test
    fun `createReleaseTag pins the run repository tag to the run commit`() = runTest {
        val (job, run, repo) = releaseTagFixture()
        coEvery { repositoryService.findByOwner(repo.ownerId) } returns listOf(repo)
        val input = io.mockk.slot<bosca.git.service.CreateTagInput>()
        coEvery { writeService.createTag(capture(input)) } returns
            bosca.git.service.CreateTagResult("6.2.0", "refs/tags/6.2.0", run.commitSha, "tag")

        mutation.createReleaseTag(authentication, job.id, repo.slug, null, null)

        assertEquals(run.commitSha, input.captured.targetRef)
    }

    @Test
    fun `createReleaseTag refuses a run without an initiating principal`() = runTest {
        val (job, _, _) = releaseTagFixture(triggeredBy = null)

        val e = assertFailsWith<IllegalStateException> {
            mutation.createReleaseTag(authentication, job.id, "test-repo", "v1", null)
        }
        assertTrue("unattributed" in (e.message ?: ""), e.message)
        coVerify(exactly = 0) { writeService.createTag(any()) }
    }

    @Test
    fun `createReleaseTag refuses a job that is not running`() = runTest {
        val (job, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)

        assertFailsWith<IllegalStateException> {
            mutation.createReleaseTag(authentication, job.id, "test-repo", "v1", null)
        }
        coVerify(exactly = 0) { writeService.createTag(any()) }
    }

    @Test
    fun `allocateBuildNumberFromJob binds the allocation to the running job source`() = runTest {
        val (job, run, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseBuildNumberRequest>()
        coEvery { deployer.allocateBuildNumber(capture(captured)) } returns
            bosca.git.service.ReleaseBuildNumberOutcome(10_001, "2.0.0", reused = false)

        val result = mutation.allocateBuildNumberFromJob(
            authentication = authentication,
            jobId = job.id,
            platform = "ios",
            applicationId = "com.example.app",
            sourceVersion = "6.2.0",
            buildKey = "release",
            minimum = "2.0.0",
        )

        assertEquals("2.0.0", result.value)
        assertEquals(repoId, captured.captured.repositoryId)
        assertEquals(run.id, captured.captured.pipelineRunId)
        assertEquals(run.commitSha, captured.captured.sourceCommitSha)
        assertEquals("6.2.0", captured.captured.sourceVersion)
        assertEquals("ios", captured.captured.platform)
        assertEquals("com.example.app", captured.captured.applicationId)
        assertEquals("release", captured.captured.buildKey)
        assertEquals("2.0.0", captured.captured.minimum)
    }

    @Test
    fun `allocateBuildNumberFromJob refuses a job that is not running`() = runTest {
        val (job, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }

        assertFailsWith<IllegalStateException> {
            mutation.allocateBuildNumberFromJob(
                authentication, job.id, "android", "io.bosca.app", "6.2.0", null, null,
            )
        }
        coVerify(exactly = 0) { deployer.allocateBuildNumber(any()) }
    }

    @Test
    fun `allocateBuildNumberFromJob fails closed when its pipeline run no longer exists`() = runTest {
        val (job, run, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { runService.findById(job.pipelineRunId) } returnsMany listOf(run, null)

        assertFailsWith<NoSuchElementException> {
            mutation.allocateBuildNumberFromJob(
                authentication, job.id, "android", "io.bosca.app", "6.2.0", null, null,
            )
        }
        coVerify(exactly = 0) { deployer.allocateBuildNumber(any()) }
    }

    @Test
    fun `allocateBuildNumberFromJob normalizes blank optional values and exposes a reused result`() = runTest {
        val (job, _, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseBuildNumberRequest>()
        coEvery { deployer.allocateBuildNumber(capture(captured)) } returns
            bosca.git.service.ReleaseBuildNumberOutcome(42, "42", reused = true)

        val result = mutation.allocateBuildNumberFromJob(
            authentication, job.id, "android", "io.bosca.app", "6.2.0", " ", " ",
        )

        assertEquals(42, result.number)
        assertEquals("42", result.value)
        assertTrue(result.reused)
        assertEquals("default", captured.captured.buildKey)
        assertEquals(null, captured.captured.minimum)

        mutation.allocateBuildNumberFromJob(
            authentication, job.id, "android", "io.bosca.app", "6.2.0", null, null,
        )
        assertEquals("default", captured.captured.buildKey)
        assertEquals(null, captured.captured.minimum)

        val controller = GitAppBuildNumberController()
        assertEquals(42, controller.number(result))
        assertEquals("42", controller.value(result))
        assertTrue(controller.reused(result))
    }

    @Test
    fun `deployFromJob passes the run context and initiator through the deployer SPI`() = runTest {
        val (job, run, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseDeployRequest>()
        coEvery { deployer.deploy(capture(captured)) } returns
            bosca.git.service.ReleaseDeployOutcome(reference = "bosca@42", status = "DEPLOYED")

        val outcome = mutation.deployFromJob(
            authentication, job.id, "production", null, "helm-values",
            kotlinx.serialization.json.buildJsonObject {
                put("resetValues", kotlinx.serialization.json.JsonPrimitive("true"))
            },
        )

        assertEquals("bosca@42", outcome.reference)
        assertEquals(repoId, captured.captured.targetRepositoryId)
        assertEquals("production", captured.captured.environmentKey)
        assertEquals("helm-values", captured.captured.target)
        assertEquals(mapOf<String, String>("resetValues" to "true"), captured.captured.overrides)
        assertEquals(run.triggeredBy, captured.captured.initiatorPrincipalId)
        assertEquals("6.2.0", captured.captured.parameters["release.version"])
    }

    @Test
    fun `deployFromJob validates its environment and normalizes blank optional inputs`() = runTest {
        val (job, _, repository) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseDeployRequest>()
        coEvery { deployer.deploy(capture(captured)) } returns
            bosca.git.service.ReleaseDeployOutcome(reference = "bosca@42", status = "DEPLOYED")

        assertFailsWith<IllegalArgumentException> {
            mutation.deployFromJob(authentication, UUID.random(), " ", null, null, null)
        }

        mutation.deployFromJob(
            authentication,
            job.id,
            "production",
            " ",
            " ",
            kotlinx.serialization.json.buildJsonObject {
                put("resetValues", kotlinx.serialization.json.JsonPrimitive("true"))
                put(
                    "nested",
                    kotlinx.serialization.json.buildJsonObject {
                        put("ignored", kotlinx.serialization.json.JsonPrimitive(true))
                    },
                )
            },
        )
        assertEquals(repoId, captured.captured.targetRepositoryId)
        assertEquals(null, captured.captured.target)
        assertEquals(mapOf("resetValues" to "true"), captured.captured.overrides)

        mutation.deployFromJob(
            authentication,
            job.id,
            "production",
            null,
            null,
            kotlinx.serialization.json.JsonPrimitive("not-an-object"),
        )
        assertEquals(emptyMap(), captured.captured.overrides)

        val sibling = repository.copy(id = UUID.random(), slug = "operations")
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns listOf(repository, sibling)
        mutation.deployFromJob(authentication, job.id, "production", sibling.slug, null, null)
        assertEquals(sibling.id, captured.captured.targetRepositoryId)

        coEvery { repositoryService.findByOwner(repository.ownerId) } returns emptyList()
        assertFailsWith<NoSuchElementException> {
            mutation.deployFromJob(authentication, job.id, "production", "missing", null, null)
        }
    }

    @Test
    fun `deployFromJob refuses a run without an initiating principal`() = runTest {
        val (job, _, _) = releaseTagFixture(triggeredBy = null)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { mockk(relaxed = true) }

        val e = assertFailsWith<IllegalStateException> {
            mutation.deployFromJob(authentication, job.id, "production", null, null, null)
        }
        assertTrue("unattributed" in (e.message ?: ""), e.message)
    }

    @Test
    fun `rollbackFromJob passes the selected repository revision overrides and initiator`() = runTest {
        val (job, run, repository) = releaseTagFixture()
        val sibling = Repository(
            id = UUID.random(), slug = "operations", name = "Operations",
            ownerId = repository.ownerId, visibility = Visibility.PRIVATE,
        )
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns listOf(repository, sibling)
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseRollbackRequest>()
        coEvery { deployer.rollback(capture(captured)) } returns
            bosca.git.service.ReleaseDeployOutcome(reference = "bosca@4", status = "ROLLED_BACK")

        val result = mutation.rollbackFromJob(
            authentication,
            job.id,
            "production",
            sibling.slug,
            "helm",
            4,
            kotlinx.serialization.json.buildJsonObject {
                put("resetValues", kotlinx.serialization.json.JsonPrimitive("true"))
            },
        )

        assertEquals("bosca@4", result.reference)
        assertEquals("ROLLED_BACK", result.status)
        assertEquals(sibling.id, captured.captured.targetRepositoryId)
        assertEquals("production", captured.captured.environmentKey)
        assertEquals("helm", captured.captured.target)
        assertEquals(4, captured.captured.toRevision)
        assertEquals(mapOf("resetValues" to "true"), captured.captured.overrides)
        assertEquals(run.triggeredBy, captured.captured.initiatorPrincipalId)

        mutation.rollbackFromJob(authentication, job.id, "production", " ", " ", null, null)
        assertEquals(repository.id, captured.captured.targetRepositoryId)
        assertEquals(null, captured.captured.target)
        assertEquals(0, captured.captured.toRevision)
        assertTrue(captured.captured.overrides.isEmpty())
    }

    @Test
    fun `rollbackFromJob validates its environment revision and running job`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }

        assertFailsWith<IllegalArgumentException> {
            mutation.rollbackFromJob(authentication, UUID.random(), " ", null, null, null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            mutation.rollbackFromJob(authentication, UUID.random(), "production", null, null, -1, null)
        }
        val (job, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)
        assertFailsWith<IllegalStateException> {
            mutation.rollbackFromJob(authentication, job.id, "production", null, null, 0, null)
        }
        coVerify(exactly = 0) { deployer.rollback(any()) }
    }

    @Test
    fun `rollbackFromJob fails closed for missing run initiator repository or deployer`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }

        val (missingRunJob, run, _) = releaseTagFixture()
        coEvery { runService.findById(missingRunJob.pipelineRunId) } returnsMany listOf(run, null)
        assertFailsWith<NoSuchElementException> {
            mutation.rollbackFromJob(authentication, missingRunJob.id, "production", null, null, 0, null)
        }

        val (unattributedJob, _, _) = releaseTagFixture(triggeredBy = null)
        val unattributed = assertFailsWith<IllegalStateException> {
            mutation.rollbackFromJob(authentication, unattributedJob.id, "production", null, null, 0, null)
        }
        assertTrue("unattributed" in unattributed.message.orEmpty())

        val (missingRepositoryJob, _, repository) = releaseTagFixture()
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns emptyList()
        assertFailsWith<NoSuchElementException> {
            mutation.rollbackFromJob(
                authentication, missingRepositoryJob.id, "production", "missing", null, 0, null,
            )
        }

        bosca.di.ProviderRegistry.clear()
        val (missingDeployerJob, _, _) = releaseTagFixture()
        val missingDeployer = assertFailsWith<IllegalStateException> {
            mutation.rollbackFromJob(authentication, missingDeployerJob.id, "production", null, null, 0, null)
        }
        assertTrue("No release deployer" in missingDeployer.message.orEmpty())
    }

    @Test
    fun `rollbackFromJob propagates adapter failure and cancellation`() = runTest {
        val (job, _, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.rollback(any()) } throws IllegalStateException("rollback unavailable")

        val failure = assertFailsWith<IllegalStateException> {
            mutation.rollbackFromJob(authentication, job.id, "production", null, null, 0, null)
        }
        assertTrue("rollback unavailable" in failure.message.orEmpty())

        coEvery { deployer.rollback(any()) } throws CancellationException("stopped")
        assertFailsWith<CancellationException> {
            mutation.rollbackFromJob(authentication, job.id, "production", null, null, 0, null)
        }
    }

    @Test
    fun `markReleasedFromJob passes the release and initiator through the deployer`() = runTest {
        val releaseId = UUID.random()
        val (job, run, _) = releaseTagFixture(releaseId = releaseId.toString())
        val initiator = requireNotNull(run.triggeredBy)
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.markReleased(releaseId, initiator) } returns Unit

        assertTrue(mutation.markReleasedFromJob(authentication, job.id))

        coVerify(exactly = 1) { deployer.markReleased(releaseId, initiator) }
    }

    @Test
    fun `markReleasedFromJob fails closed for invalid job and release context`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }

        val (notRunning, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)
        assertFailsWith<IllegalStateException> { mutation.markReleasedFromJob(authentication, notRunning.id) }

        val (missingRunJob, run, _) = releaseTagFixture()
        coEvery { runService.findById(missingRunJob.pipelineRunId) } returnsMany listOf(run, null)
        assertFailsWith<NoSuchElementException> { mutation.markReleasedFromJob(authentication, missingRunJob.id) }

        val (unattributed, _, _) = releaseTagFixture(triggeredBy = null)
        assertFailsWith<IllegalStateException> { mutation.markReleasedFromJob(authentication, unattributed.id) }

        for (releaseId in listOf(null, " ", "not-a-uuid")) {
            val (job, _, _) = releaseTagFixture(releaseId = releaseId)
            assertFailsWith<IllegalArgumentException> { mutation.markReleasedFromJob(authentication, job.id) }
        }
        coVerify(exactly = 0) { deployer.markReleased(any(), any()) }
    }

    @Test
    fun `markReleasedFromJob propagates deployer failure and cancellation`() = runTest {
        val releaseId = UUID.random()
        val (job, _, _) = releaseTagFixture(releaseId = releaseId.toString())
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.markReleased(any(), any()) } throws IllegalStateException("release unavailable")
        assertFailsWith<IllegalStateException> { mutation.markReleasedFromJob(authentication, job.id) }

        coEvery { deployer.markReleased(any(), any()) } throws CancellationException("stopped")
        assertFailsWith<CancellationException> { mutation.markReleasedFromJob(authentication, job.id) }
    }

    @Test
    fun `generateReleaseNotesFromJob passes the release and initiator through the deployer`() = runTest {
        val releaseId = UUID.random()
        val (job, run, _) = releaseTagFixture(releaseId = releaseId.toString())
        val initiator = requireNotNull(run.triggeredBy)
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.generateReleaseNotes(releaseId, initiator) } returns Unit

        assertTrue(mutation.generateReleaseNotesFromJob(authentication, job.id))

        coVerify(exactly = 1) { deployer.generateReleaseNotes(releaseId, initiator) }
    }

    @Test
    fun `generateReleaseNotesFromJob fails closed for invalid job and release context`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }

        val (notRunning, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)
        assertFailsWith<IllegalStateException> { mutation.generateReleaseNotesFromJob(authentication, notRunning.id) }

        val (missingRunJob, run, _) = releaseTagFixture()
        coEvery { runService.findById(missingRunJob.pipelineRunId) } returnsMany listOf(run, null)
        assertFailsWith<NoSuchElementException> { mutation.generateReleaseNotesFromJob(authentication, missingRunJob.id) }

        val (unattributed, _, _) = releaseTagFixture(triggeredBy = null)
        assertFailsWith<IllegalStateException> { mutation.generateReleaseNotesFromJob(authentication, unattributed.id) }

        for (releaseId in listOf(null, " ", "not-a-uuid")) {
            val (job, _, _) = releaseTagFixture(releaseId = releaseId)
            assertFailsWith<IllegalArgumentException> { mutation.generateReleaseNotesFromJob(authentication, job.id) }
        }
        coVerify(exactly = 0) { deployer.generateReleaseNotes(any(), any()) }
    }

    @Test
    fun `generateReleaseNotesFromJob propagates deployer failure and cancellation`() = runTest {
        val (job, _, _) = releaseTagFixture(releaseId = UUID.random().toString())
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.generateReleaseNotes(any(), any()) } throws IllegalStateException("notes unavailable")
        assertFailsWith<IllegalStateException> { mutation.generateReleaseNotesFromJob(authentication, job.id) }

        coEvery { deployer.generateReleaseNotes(any(), any()) } throws CancellationException("stopped")
        assertFailsWith<CancellationException> { mutation.generateReleaseNotesFromJob(authentication, job.id) }
    }

    @Test
    fun `playRolloutFromJob passes the store-neutral percentage and run initiator through the deployer`() = runTest {
        val (job, run, repository) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleasePlayRolloutRequest>()
        coEvery { deployer.playRollout(capture(captured)) } returns
            bosca.git.service.ReleaseDeployOutcome(reference = "app:production@42 (10%)", status = "DEPLOYED")

        val outcome = mutation.playRolloutFromJob(
            authentication, job.id, "production", 10.0, null, "google_play",
        )

        assertEquals("app:production@42 (10%)", outcome.reference)
        assertEquals(repoId, captured.captured.targetRepositoryId)
        assertEquals("production", captured.captured.environmentKey)
        assertEquals("google_play", captured.captured.target)
        assertEquals(10.0, captured.captured.rolloutPercentage)
        assertEquals(run.triggeredBy, captured.captured.initiatorPrincipalId)

        val sibling = Repository(
            id = UUID.random(), slug = "mobile", name = "Mobile",
            ownerId = repository.ownerId, visibility = Visibility.PRIVATE,
        )
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns listOf(repository, sibling)
        mutation.playRolloutFromJob(authentication, job.id, "production", 37.5, sibling.slug, " ")

        assertEquals(sibling.id, captured.captured.targetRepositoryId)
        assertEquals(null, captured.captured.target)
        assertEquals(37.5, captured.captured.rolloutPercentage)
    }

    @Test
    fun `playRolloutFromJob validates percentage and running job before invoking the deployer`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                mutation.playRolloutFromJob(authentication, UUID.random(), "production", invalid, null, null)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            mutation.playRolloutFromJob(authentication, UUID.random(), " ", 10.0, null, null)
        }

        val (job, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)
        assertFailsWith<IllegalStateException> {
            mutation.playRolloutFromJob(authentication, job.id, "production", 10.0, null, null)
        }
        coVerify(exactly = 0) { deployer.playRollout(any()) }
    }

    @Test
    fun `playRolloutFromJob fails closed for missing run initiator repository or deployer`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }

        val (missingRunJob, missingRun, _) = releaseTagFixture()
        coEvery { runService.findById(missingRunJob.pipelineRunId) } returnsMany listOf(missingRun, null)
        assertFailsWith<NoSuchElementException> {
            mutation.playRolloutFromJob(authentication, missingRunJob.id, "production", 10.0, null, null)
        }

        val (unattributedJob, _, _) = releaseTagFixture(triggeredBy = null)
        val unattributed = assertFailsWith<IllegalStateException> {
            mutation.playRolloutFromJob(authentication, unattributedJob.id, "production", 10.0, null, null)
        }
        assertTrue("unattributed" in unattributed.message.orEmpty())

        val (missingRepositoryJob, _, repository) = releaseTagFixture()
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns emptyList()
        assertFailsWith<NoSuchElementException> {
            mutation.playRolloutFromJob(
                authentication, missingRepositoryJob.id, "production", 10.0, "missing", null,
            )
        }

        bosca.di.ProviderRegistry.clear()
        val (missingDeployerJob, _, _) = releaseTagFixture()
        val missingDeployer = assertFailsWith<IllegalStateException> {
            mutation.playRolloutFromJob(authentication, missingDeployerJob.id, "production", 10.0, null, null)
        }
        assertTrue("No release deployer" in missingDeployer.message.orEmpty())
    }

    @Test
    fun `playRolloutFromJob propagates deployer failure and cancellation`() = runTest {
        val (job, _, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.playRollout(any()) } throws IllegalStateException("Play unavailable")

        val failure = assertFailsWith<IllegalStateException> {
            mutation.playRolloutFromJob(authentication, job.id, "production", 10.0, null, null)
        }
        assertTrue("Play unavailable" in failure.message.orEmpty())

        coEvery { deployer.playRollout(any()) } throws CancellationException("stopped")
        assertFailsWith<CancellationException> {
            mutation.playRolloutFromJob(authentication, job.id, "production", 10.0, null, null)
        }
    }

    @Test
    fun `appStoreReviewFromJob passes typed mode repository and initiator through the deployer`() = runTest {
        val (job, run, repository) = releaseTagFixture()
        val sibling = repository.copy(id = UUID.random(), slug = "mobile")
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns listOf(repository, sibling)
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseAppStoreReviewRequest>()
        coEvery { deployer.appStoreReview(capture(captured)) } returns
            bosca.git.service.ReleaseAppStoreReviewOutcome("IN_REVIEW", complete = false, approved = false)

        val outcome = mutation.appStoreReviewFromJob(
            authentication,
            job.id,
            "production",
            bosca.git.service.ReleaseAppStoreReviewMode.BETA,
            sibling.slug,
            " ",
        )

        assertEquals("IN_REVIEW", outcome.state)
        assertEquals(false, outcome.complete)
        assertEquals(sibling.id, captured.captured.targetRepositoryId)
        assertEquals("production", captured.captured.environmentKey)
        assertEquals(null, captured.captured.target)
        assertEquals(bosca.git.service.ReleaseAppStoreReviewMode.BETA, captured.captured.mode)
        assertEquals(run.triggeredBy, captured.captured.initiatorPrincipalId)
        assertEquals("6.2.0", captured.captured.parameters["release.version"])

        mutation.appStoreReviewFromJob(
            authentication,
            job.id,
            "production",
            bosca.git.service.ReleaseAppStoreReviewMode.APP_STORE,
            " ",
            "app-store",
        )
        assertEquals(repository.id, captured.captured.targetRepositoryId)
        assertEquals("app-store", captured.captured.target)
    }

    @Test
    fun `storeHealthFromJob passes a one-shot duration threshold and initiator through the deployer`() = runTest {
        val (job, run, _) = releaseTagFixture()
        val deployer = mockk<bosca.git.service.ReleaseDeployer>()
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        val captured = slot<bosca.git.service.ReleaseStoreHealthRequest>()
        coEvery { deployer.storeHealth(capture(captured)) } returns
            bosca.git.service.ReleaseStoreHealthOutcome(0.25, 0.5, 86_400, healthy = true)

        val outcome = mutation.storeHealthFromJob(
            authentication, job.id, "production", 0.5, 86_400, null, "google_play",
        )

        assertEquals(0.25, outcome.crashRate)
        assertEquals(0.5, outcome.maxCrashRate)
        assertEquals(86_400, outcome.windowSeconds)
        assertTrue(outcome.healthy)
        assertEquals(repoId, captured.captured.targetRepositoryId)
        assertEquals(java.time.Duration.ofDays(1), captured.captured.window)
        assertEquals("google_play", captured.captured.target)
        assertEquals(run.triggeredBy, captured.captured.initiatorPrincipalId)

        mutation.storeHealthFromJob(
            authentication, job.id, "production", 0.5, 60, " ", " ",
        )
        assertEquals(repoId, captured.captured.targetRepositoryId)
        assertEquals(null, captured.captured.target)
    }

    @Test
    fun `store observation mutations validate input running state and attribution`() = runTest {
        val deployer = mockk<bosca.git.service.ReleaseDeployer>(relaxed = true)
        provides<bosca.git.service.ReleaseDeployer>(singleton = true) { deployer }
        assertFailsWith<IllegalArgumentException> {
            mutation.appStoreReviewFromJob(
                authentication, UUID.random(), " ", bosca.git.service.ReleaseAppStoreReviewMode.APP_STORE, null, null,
            )
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 100.1).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                mutation.storeHealthFromJob(authentication, UUID.random(), "production", invalid, 60, null, null)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            mutation.storeHealthFromJob(authentication, UUID.random(), "production", 0.5, 0, null, null)
        }
        val (stopped, _, _) = releaseTagFixture(jobStatus = PipelineRunStatus.SUCCESS)
        assertFailsWith<IllegalStateException> {
            mutation.appStoreReviewFromJob(
                authentication, stopped.id, "production", bosca.git.service.ReleaseAppStoreReviewMode.BETA, null, null,
            )
        }
        val (unattributed, _, _) = releaseTagFixture(triggeredBy = null)
        assertTrue(
            "unattributed" in assertFailsWith<IllegalStateException> {
                mutation.storeHealthFromJob(authentication, unattributed.id, "production", 0.5, 60, null, null)
            }.message.orEmpty(),
        )
        coVerify(exactly = 0) { deployer.appStoreReview(any()) }
        coVerify(exactly = 0) { deployer.storeHealth(any()) }
    }

    @Test
    fun `createReleaseTag requires a tag name when the run carries no release version`() = runTest {
        val (job, _, _) = releaseTagFixture(releaseVersion = null)

        val e = assertFailsWith<IllegalArgumentException> {
            mutation.createReleaseTag(authentication, job.id, "test-repo", null, null)
        }
        assertTrue("release.version" in (e.message ?: ""), e.message)
    }

    private fun authenticate(principalId: UUID, admin: Boolean = false) {
        val groups = if (admin) {
            listOf(Group(UUID.random(), "administrators", "Administrators", GroupType.PRINCIPAL))
        } else {
            emptyList()
        }
        every { authentication.principal() } returns AuthenticatedPrincipal(Principal(id = principalId), groups)
    }

    private fun authenticateScoped(credentialId: Long) {
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = listOf("ci:read", "ci:execute"),
            allowedGroupIds = null,
            credentialId = credentialId,
        )
    }

    private suspend fun eligiblePrincipal(principalId: UUID) {
        coEvery { securityService.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { securityService.getPrincipalGroups(principalId) } returns emptyList()
        coEvery {
            permissionEvaluator.isAllowed(
                any<AuthenticationContext>(),
                any<Repository>(),
                bosca.security.model.PermissionAction.EXECUTE,
            )
        } returns true
    }

    private suspend fun scheduleFixture(schedule: ScheduledJob) {
        coEvery { scheduleService.findById(schedule.id) } returns schedule
        coEvery { pipelineService.findById(pipelineId) } returns testPipeline()
        coEvery { repositoryService.findById(repoId) } returns testRepo()
    }

    private fun scheduleJob(
        state: ScheduledJobPrincipalState,
        principalId: UUID? = null,
    ) = ScheduledJob(
        id = UUID.random(),
        name = "Git pipeline schedule: Build",
        jobName = PipelineScheduleJob.NAME,
        jobParameters = Json.encodeToJsonElement(PipelineScheduleJob.serializer(), PipelineScheduleJob(pipelineId)),
        cronExpression = "0 3 * * *",
        enabled = state == ScheduledJobPrincipalState.ACTIVE,
        createdAt = bosca.serialization.OffsetDateTime.now(),
        updatedAt = bosca.serialization.OffsetDateTime.now(),
        createdBy = UUID.NIL,
        executionPrincipalId = principalId,
        principalState = state,
        principalAssignedBy = principalId,
        principalConfirmedBy = principalId?.takeIf { state == ScheduledJobPrincipalState.ACTIVE },
    )

    private fun testRepo() = Repository(
        id = repoId,
        slug = "test-repo",
        name = "Test",
        ownerId = UUID.random(),
        visibility = Visibility.PRIVATE
    )

    private fun testPipeline() = Pipeline(
        id = pipelineId,
        repositoryId = repoId,
        filePath = ".bosca/pipelines/build.yaml",
        name = "Build",
        triggers = listOf(PipelineTrigger(type = PipelineTriggerType.PUSH)).toJsonElement(),
        configHash = "abc"
    )

    private fun testDefinition() = PipelineDefinition(
        name = "Build",
        jobs = mapOf("build" to JobDefinition(steps = listOf(StepDefinition(name = "B", run = "b"))))
    )

    private fun testRun(id: UUID = UUID.random()) = PipelineRun(
        id = id,
        pipelineId = pipelineId,
        repositoryId = repoId,
        commitSha = "abc",
        ref = "main",
        triggerType = PipelineTriggerType.PUSH,
        status = PipelineRunStatus.QUEUED,
        number = 1
    )

    private fun testAgent(
        id: UUID = UUID.random(),
        mode: AgentMode = AgentMode.RUNNER,
        ephemeral: Boolean = false,
        apiTokenCredentialId: Long? = null,
        parentAgentId: UUID? = null,
        providerConfig: String? = null,
    ) = PipelineAgent(
        id = id,
        name = "agent",
        labels = listOf("linux"),
        mode = mode,
        status = AgentStatus.ONLINE,
        ephemeral = ephemeral,
        parentAgentId = parentAgentId,
        tokenHash = "hash",
        providerConfig = providerConfig,
        apiTokenCredentialId = apiTokenCredentialId,
    )

    private fun testJob(
        agentId: UUID? = null,
        status: PipelineRunStatus = PipelineRunStatus.QUEUED
    ) = PipelineJob(
        id = UUID.random(),
        pipelineRunId = UUID.random(),
        name = "build",
        status = status,
        runnerLabel = "linux",
        agentId = agentId
    )

    private fun testOrchestratorInput() = OrchestratorConfigInput(
        provider = "digitalocean",
        credentials = bosca.git.model.ProviderCredentials(),
        defaults = bosca.git.model.VmDefaults(region = "nyc3", size = "s-2vcpu-4gb", image = "ubuntu-24-04-x64")
    )
}
