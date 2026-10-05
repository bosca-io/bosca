package bosca.git.ci.graphql

import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.git.model.AgentStatus
import bosca.git.model.Pipeline
import bosca.git.model.PipelineAgent
import bosca.git.model.PipelineJob
import bosca.git.model.PipelineInputDefinition
import bosca.git.model.PipelineInputType
import bosca.git.model.PipelineRun
import bosca.git.model.PipelineRunStatus
import bosca.git.model.PipelineSecret
import bosca.git.model.PipelineTriggerType
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineTrigger
import bosca.git.model.TriggerInput
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.security.RepositoryPermissionEvaluator
import bosca.git.service.PipelineAgentService
import bosca.git.service.PipelineJobService
import bosca.git.service.PipelineLogService
import bosca.git.service.PipelineRunService
import bosca.git.service.PipelineSecretService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryService
import bosca.git.service.ReleaseDeployer
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ScopedAuthenticatedPrincipal
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class GitPipelineQueryTest {

    private val pipelineService = mockk<PipelineService>(relaxed = true)
    private val runService = mockk<PipelineRunService>(relaxed = true)
    private val jobService = mockk<PipelineJobService>(relaxed = true)
    private val agentService = mockk<PipelineAgentService>(relaxed = true)
    private val secretService = mockk<PipelineSecretService>(relaxed = true)
    private val logService = mockk<PipelineLogService>(relaxed = true)
    private val repositoryService = mockk<RepositoryService>(relaxed = true)
    private val permissionEvaluator = mockk<RepositoryPermissionEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)

    private lateinit var query: GitPipelineQuery

    private val repoId = UUID.random()

    @BeforeTest
    fun setup() {
        bosca.di.ProviderRegistry.clear()
        coEvery { repositoryService.findById(repoId) } returns testRepo()
        query = GitPipelineQuery(
            pipelineService, runService, jobService, agentService,
            secretService, logService, repositoryService, permissionEvaluator,
        )
    }

    @Test
    fun `pipelines verifies VIEW permission and returns results`() = runTest {
        val pipelines = listOf(
            Pipeline(id = UUID.random(), repositoryId = repoId, filePath = "a.yaml", name = "A", configHash = "h1"),
            Pipeline(id = UUID.random(), repositoryId = repoId, filePath = "b.yaml", name = "B", configHash = "h2")
        )
        coEvery { pipelineService.findByRepository(repoId) } returns pipelines

        val result = query.pipelines(authentication, repoId)

        assertEquals(2, result.size)
        coVerify { permissionEvaluator.verifyAllowed(authentication, any(), any()) }
    }

    @Test
    fun `pipelines throws when repository not found`() = runTest {
        coEvery { repositoryService.findById(any()) } returns null

        assertFailsWith<NoSuchElementException> {
            query.pipelines(authentication, UUID.random())
        }
    }

    @Test
    fun `pipelineInputs reads manual declarations at the selected ref after VIEW authorization`() = runTest {
        val pipeline = Pipeline(id = UUID.random(), repositoryId = repoId, filePath = "image.yaml", name = "Image", configHash = "hash")
        coEvery { pipelineService.findById(pipeline.id) } returns pipeline
        coEvery { pipelineService.parseDefinition(repoId, "refs/tags/7.4.0", pipeline.filePath) } returns PipelineDefinition(
            name = "Image",
            triggers = listOf(
                PipelineTrigger(PipelineTriggerType.MANUAL, inputs = mapOf("image" to TriggerInput(type = "choice", options = listOf("bosca-server")))),
                PipelineTrigger(PipelineTriggerType.RELEASE, inputs = mapOf("releaseOnly" to TriggerInput())),
            ),
            jobs = emptyMap(),
        )
        val result = query.pipelineInputs(authentication, pipeline.id, "refs/tags/7.4.0")
        assertEquals(listOf(PipelineInputDefinition("image", TriggerInput(type = "choice", options = listOf("bosca-server")))), result)
        val controller = GitPipelineInputController()
        assertEquals("image", controller.name(result.single()))
        assertEquals(PipelineInputType.CHOICE, controller.type(result.single()))
        assertEquals(listOf("bosca-server"), controller.options(result.single()))
        assertNull(controller.defaultValue(result.single()))
        assertTrue(controller.required(result.single()))
        coVerify { permissionEvaluator.verifyAllowed(authentication, any<Repository>(), PermissionAction.VIEW) }

        coEvery { permissionEvaluator.verifyAllowed(authentication, any<Repository>(), PermissionAction.VIEW) } throws SecurityException("Forbidden")
        assertFailsWith<SecurityException> { query.pipelineInputs(authentication, pipeline.id, "main") }
        coVerify(exactly = 0) { pipelineService.parseDefinition(repoId, "main", pipeline.filePath) }
    }

    @Test
    fun `pipelineInputs rejects a ref that does not support manual runs`() = runTest {
        val pipeline = Pipeline(id = UUID.random(), repositoryId = repoId, filePath = "image.yaml", name = "Image", configHash = "hash")
        coEvery { pipelineService.findById(pipeline.id) } returns pipeline
        coEvery { pipelineService.parseDefinition(repoId, "old-tag", pipeline.filePath) } returns PipelineDefinition(
            name = "Image", triggers = listOf(PipelineTrigger(PipelineTriggerType.RELEASE)), jobs = emptyMap(),
        )
        assertFailsWith<IllegalStateException> { query.pipelineInputs(authentication, pipeline.id, "old-tag") }
    }

    @Test
    fun `pipelineRun returns run and verifies permission`() = runTest {
        val run = testRun()
        coEvery { runService.findById(run.id) } returns run

        val result = query.pipelineRun(authentication, run.id)

        assertNotNull(result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, any(), any()) }
    }

    @Test
    fun `pipelineRun returns null when not found`() = runTest {
        coEvery { runService.findById(any()) } returns null

        assertNull(query.pipelineRun(authentication, UUID.random()))
    }

    @Test
    fun `targetedJobStatus returns terminal state to the assigned ephemeral agent`() = runTest {
        val agentId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "cancelled",
            runnerLabel = "linux",
            status = PipelineRunStatus.CANCELLED,
        )
        val credentialId = 42L
        coEvery { agentService.findById(agentId) } returns PipelineAgent(
            id = agentId,
            name = "ephemeral",
            ephemeral = true,
            jobId = job.id,
            tokenHash = "unused",
            apiTokenCredentialId = credentialId,
        )
        coEvery { jobService.findById(job.id) } returns job
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = listOf("ci:execute"),
            allowedGroupIds = null,
            credentialId = credentialId,
        )

        assertEquals(
            PipelineRunStatus.CANCELLED,
            query.targetedJobStatus(authentication, agentId, job.id),
        )
        coVerify { permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE) }
    }

    @Test
    fun `targetedJobStatus returns cancellation to the assigned persistent agent`() = runTest {
        val agentId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "cancelled",
            runnerLabel = "linux",
            status = PipelineRunStatus.CANCELLED,
            agentId = agentId,
        )
        coEvery { agentService.findById(agentId) } returns PipelineAgent(
            id = agentId,
            name = "persistent",
            ephemeral = false,
            tokenHash = "unused",
        )
        coEvery { jobService.findById(job.id) } returns job

        assertEquals(
            PipelineRunStatus.CANCELLED,
            query.targetedJobStatus(authentication, agentId, job.id),
        )
    }

    @Test
    fun `targetedJobStatus rejects a job assigned to a different persistent agent`() = runTest {
        val agentId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "running",
            runnerLabel = "linux",
            status = PipelineRunStatus.RUNNING,
            agentId = UUID.random(),
        )
        coEvery { agentService.findById(agentId) } returns PipelineAgent(
            id = agentId,
            name = "persistent",
            ephemeral = false,
            tokenHash = "unused",
        )
        coEvery { jobService.findById(job.id) } returns job

        assertFailsWith<SecurityException> {
            query.targetedJobStatus(authentication, agentId, job.id)
        }
    }

    @Test
    fun `targetedJobStatus reports a stale persistent attempt as cancelled after rerun`() = runTest {
        val agentId = UUID.random()
        val job = PipelineJob(
            id = UUID.random(),
            pipelineRunId = UUID.random(),
            name = "rerun",
            runnerLabel = "linux",
            status = PipelineRunStatus.QUEUED,
            attempt = 2,
            agentId = null,
        )
        coEvery { agentService.findById(agentId) } returns PipelineAgent(
            id = agentId,
            name = "persistent",
            ephemeral = false,
            tokenHash = "unused",
        )
        coEvery { jobService.findById(job.id) } returns job

        assertEquals(
            PipelineRunStatus.CANCELLED,
            query.targetedJobStatus(authentication, agentId, job.id, attempt = 1),
        )
    }

    @Test
    fun `targetedJobStatus rejects a job outside the ephemeral agent scope`() = runTest {
        val agentId = UUID.random()
        coEvery { agentService.findById(agentId) } returns PipelineAgent(
            id = agentId,
            name = "ephemeral",
            ephemeral = true,
            jobId = UUID.random(),
            tokenHash = "unused",
        )

        assertFailsWith<SecurityException> {
            query.targetedJobStatus(authentication, agentId, UUID.random())
        }
        coVerify(exactly = 0) { jobService.findById(any()) }
    }

    @Test
    fun `pipelineRuns applies pagination defaults`() = runTest {
        coEvery { runService.findByRepository(repoId, 0, 25) } returns emptyList()

        val result = query.pipelineRuns(authentication, repoId, null, null)

        assertTrue(result.isEmpty())
        coVerify { runService.findByRepository(repoId, 0, 25) }
    }

    @Test
    fun `pipelineRuns coerces limit to valid range`() = runTest {
        coEvery { runService.findByRepository(repoId, 0, 100) } returns emptyList()

        query.pipelineRuns(authentication, repoId, null, 999)

        coVerify { runService.findByRepository(repoId, 0, 100) }
    }

    @Test
    fun `pipelineAgents lets an interactive administrator list all agents`() = runTest {
        val agents = listOf(
            PipelineAgent(id = UUID.random(), name = "a1", tokenHash = "h", status = AgentStatus.ONLINE)
        )
        every { authentication.principal() } returns null
        coEvery { agentService.listAgents(AgentStatus.ONLINE) } returns agents

        val result = query.pipelineAgents(authentication, AgentStatus.ONLINE)

        assertEquals(1, result.size)
        coVerify { permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE) }
    }

    @Test
    fun `pipelineAgents lets a scoped CI manager list all agents`() = runTest {
        val agents = listOf(
            PipelineAgent(id = UUID.random(), name = "a1", tokenHash = "h", status = AgentStatus.ONLINE)
        )
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = listOf("ci:manage"),
            allowedGroupIds = null,
            credentialId = 7L,
        )
        coEvery { agentService.listAgents(AgentStatus.ONLINE) } returns agents

        val result = query.pipelineAgents(authentication, AgentStatus.ONLINE)

        assertEquals(agents, result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, PermissionAction.MANAGE) }
    }

    @Test
    fun `pipelineAgents scopes an agent credential to itself and its children`() = runTest {
        val credentialId = 42L
        val caller = PipelineAgent(
            id = UUID.random(),
            name = "orchestrator",
            tokenHash = "caller",
            status = AgentStatus.ONLINE,
            apiTokenCredentialId = credentialId,
        )
        val child = PipelineAgent(
            id = UUID.random(),
            name = "ephemeral",
            tokenHash = "child",
            status = AgentStatus.BUSY,
            parentAgentId = caller.id,
        )
        val unrelated = PipelineAgent(
            id = UUID.random(),
            name = "unrelated",
            tokenHash = "unrelated",
            status = AgentStatus.ONLINE,
        )
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = listOf("ci:execute"),
            allowedGroupIds = null,
            credentialId = credentialId,
        )
        coEvery { agentService.listAgents() } returns listOf(caller, child, unrelated)

        val result = query.pipelineAgents(authentication, null)

        assertEquals(listOf(caller, child), result)
        coVerify { permissionEvaluator.verifyAllowed(authentication, PermissionAction.EXECUTE) }
    }

    @Test
    fun `pipelineAgents applies status after scoping an agent credential`() = runTest {
        val credentialId = 42L
        val caller = PipelineAgent(
            id = UUID.random(),
            name = "orchestrator",
            tokenHash = "caller",
            status = AgentStatus.ONLINE,
            apiTokenCredentialId = credentialId,
        )
        val busyChild = PipelineAgent(
            id = UUID.random(),
            name = "busy-child",
            tokenHash = "busy-child",
            status = AgentStatus.BUSY,
            parentAgentId = caller.id,
        )
        val onlineChild = PipelineAgent(
            id = UUID.random(),
            name = "online-child",
            tokenHash = "online-child",
            status = AgentStatus.ONLINE,
            parentAgentId = caller.id,
        )
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = listOf("ci:execute"),
            allowedGroupIds = null,
            credentialId = credentialId,
        )
        coEvery { agentService.listAgents() } returns listOf(caller, busyChild, onlineChild)

        val result = query.pipelineAgents(authentication, AgentStatus.BUSY)

        assertEquals(listOf(busyChild), result)
    }

    @Test
    fun `pipelineAgents rejects a scoped token that is not assigned to an agent`() = runTest {
        every { authentication.principal() } returns ScopedAuthenticatedPrincipal(
            principal = Principal(id = UUID.random()),
            allGroups = emptyList(),
            scopes = listOf("ci:execute"),
            allowedGroupIds = null,
            credentialId = 99L,
        )
        coEvery { agentService.listAgents() } returns listOf(
            PipelineAgent(
                id = UUID.random(),
                name = "other-agent",
                tokenHash = "other",
                apiTokenCredentialId = 42L,
            )
        )

        assertFailsWith<SecurityException> {
            query.pipelineAgents(authentication, null)
        }
    }

    @Test
    fun `pipelineSecrets verifies MANAGE permission`() = runTest {
        val secrets = listOf(
            PipelineSecret(repositoryId = repoId, name = "KEY", encryptedValue = "enc")
        )
        coEvery { secretService.listSecrets(repoId) } returns secrets

        val result = query.pipelineSecrets(authentication, repoId)

        assertEquals(1, result.size)
        coVerify { permissionEvaluator.verifyAllowed(authentication, any(), any()) }
    }

    @Test
    fun `pipelineLogs delegates to log service`() = runTest {
        val stepId = UUID.random()
        coEvery { logService.getLogs(any(), any(), any(), stepId, 0, 1000) } returns emptyList()

        val result = query.pipelineLogs(authentication, stepId, null, null, false, null)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `pipelineLogs coerces limit`() = runTest {
        val stepId = UUID.random()
        query.pipelineLogs(authentication, stepId, null, 50000, false, null)
        coVerify { logService.getLogs(any(), any(), any(), stepId, 0, 10000) }
    }

    @Test
    fun `pipelineLogs passes beforeLine through to the log service`() = runTest {
        val stepId = UUID.random()
        query.pipelineLogs(authentication, stepId, null, 100, null, 4200)
        coVerify { logService.getLogs(any(), any(), any(), stepId, 0, 100, false, 4200) }
    }

    @Test
    fun `jobDeploymentHealth passes target repository environment and run initiator to deployer`() = runTest {
        val (job, run, repository) = healthFixture()
        val deployer = mockk<ReleaseDeployer>()
        provides<ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.deploymentHealth(any(), any(), any()) } returns "HEALTHY"
        val initiator = requireNotNull(run.triggeredBy)

        assertEquals("HEALTHY", query.jobDeploymentHealth(authentication, job.id, "production", null))
        assertEquals("HEALTHY", query.jobDeploymentHealth(authentication, job.id, "production", " "))
        assertEquals("HEALTHY", query.jobDeploymentHealth(authentication, job.id, "production", repository.slug))
        coVerify(exactly = 3) { deployer.deploymentHealth(repository.id, "production", initiator) }

        val sibling = repository.copy(id = UUID.random(), slug = "operations")
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns listOf(repository, sibling)
        assertEquals(
            "HEALTHY",
            query.jobDeploymentHealth(authentication, job.id, "production", sibling.slug),
        )
        coVerify { deployer.deploymentHealth(sibling.id, "production", initiator) }

        val qualified = repository.copy(id = UUID.random(), slug = "mobile")
        coEvery { repositoryService.findByOwnerAndSlug("bosca", "mobile") } returns qualified
        assertEquals(
            "HEALTHY",
            query.jobDeploymentHealth(authentication, job.id, "production", "bosca/mobile"),
        )
        coVerify { deployer.deploymentHealth(qualified.id, "production", initiator) }
    }

    @Test
    fun `jobDeploymentHealth validates environment and running job before observing`() = runTest {
        val deployer = mockk<ReleaseDeployer>(relaxed = true)
        provides<ReleaseDeployer>(singleton = true) { deployer }

        assertFailsWith<IllegalArgumentException> {
            query.jobDeploymentHealth(authentication, UUID.random(), " ", null)
        }
        val missingJobId = UUID.random()
        coEvery { jobService.findById(missingJobId) } returns null
        assertFailsWith<NoSuchElementException> {
            query.jobDeploymentHealth(authentication, missingJobId, "production", null)
        }
        val (job, _, _) = healthFixture(status = PipelineRunStatus.SUCCESS)
        assertFailsWith<IllegalStateException> {
            query.jobDeploymentHealth(authentication, job.id, "production", null)
        }
        coVerify(exactly = 0) { deployer.deploymentHealth(any(), any(), any()) }
    }

    @Test
    fun `jobDeploymentHealth fails closed for missing run initiator repository or deployer`() = runTest {
        val deployer = mockk<ReleaseDeployer>(relaxed = true)
        provides<ReleaseDeployer>(singleton = true) { deployer }

        val (missingRunJob, _, _) = healthFixture()
        coEvery { runService.findById(missingRunJob.pipelineRunId) } returns null
        assertFailsWith<NoSuchElementException> {
            query.jobDeploymentHealth(authentication, missingRunJob.id, "production", null)
        }

        val (unattributedJob, _, _) = healthFixture(triggeredBy = null)
        val unattributed = assertFailsWith<IllegalStateException> {
            query.jobDeploymentHealth(authentication, unattributedJob.id, "production", null)
        }
        assertTrue("unattributed" in unattributed.message.orEmpty())

        val (missingRunRepositoryJob, run, _) = healthFixture()
        coEvery { repositoryService.findById(run.repositoryId) } returns null
        assertFailsWith<NoSuchElementException> {
            query.jobDeploymentHealth(authentication, missingRunRepositoryJob.id, "production", null)
        }

        val (missingTargetJob, _, repository) = healthFixture()
        coEvery { repositoryService.findByOwner(repository.ownerId) } returns emptyList()
        assertFailsWith<NoSuchElementException> {
            query.jobDeploymentHealth(authentication, missingTargetJob.id, "production", "missing")
        }

        bosca.di.ProviderRegistry.clear()
        val (missingDeployerJob, _, _) = healthFixture()
        val missingDeployer = assertFailsWith<IllegalStateException> {
            query.jobDeploymentHealth(authentication, missingDeployerJob.id, "production", null)
        }
        assertTrue("No release deployer" in missingDeployer.message.orEmpty())
    }

    @Test
    fun `jobDeploymentHealth propagates deployer failure and cancellation`() = runTest {
        val (job, _, _) = healthFixture()
        val deployer = mockk<ReleaseDeployer>()
        provides<ReleaseDeployer>(singleton = true) { deployer }
        coEvery { deployer.deploymentHealth(any(), any(), any()) } throws IllegalStateException("probe unavailable")
        assertFailsWith<IllegalStateException> {
            query.jobDeploymentHealth(authentication, job.id, "production", null)
        }

        coEvery { deployer.deploymentHealth(any(), any(), any()) } throws CancellationException("stopped")
        assertFailsWith<CancellationException> {
            query.jobDeploymentHealth(authentication, job.id, "production", null)
        }
    }

    private fun healthFixture(
        status: PipelineRunStatus = PipelineRunStatus.RUNNING,
        triggeredBy: UUID? = UUID.random(),
    ): Triple<PipelineJob, PipelineRun, Repository> {
        val repository = testRepo()
        val job = PipelineJob(
            id = UUID.random(), pipelineRunId = UUID.random(), name = "verify", status = status,
        )
        val run = testRun(id = job.pipelineRunId).copy(triggeredBy = triggeredBy)
        coEvery { jobService.findById(job.id) } returns job
        coEvery { runService.findById(job.pipelineRunId) } returns run
        coEvery { repositoryService.findById(run.repositoryId) } returns repository
        return Triple(job, run, repository)
    }

    private fun testRepo() = Repository(
        id = repoId, slug = "test", name = "Test",
        ownerId = UUID.random(), visibility = Visibility.PRIVATE
    )

    private fun testRun(id: UUID = UUID.random()) = PipelineRun(
        id = id, pipelineId = UUID.random(), repositoryId = repoId,
        commitSha = "abc", ref = "main", triggerType = PipelineTriggerType.PUSH,
        status = PipelineRunStatus.QUEUED, number = 1
    )
}
